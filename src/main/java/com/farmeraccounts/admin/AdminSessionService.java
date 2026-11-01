package com.farmeraccounts.admin;

import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.common.Hashing;
import com.farmeraccounts.common.RateLimiter;
import com.farmeraccounts.config.AppProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side admin sessions: a random token in an HttpOnly cookie, stored only as a hash, with an
 * idle timeout, an absolute lifetime and explicit revocation on logout.
 */
@Service
public class AdminSessionService {

    public static final String COOKIE = "FA_ADMIN_SESSION";

    public record LoginResult(String token, AdminView admin, Duration maxAge) {}

    public record AdminView(UUID id, String username, String role, boolean totpEnabled) {}

    record AdminRow(UUID id, String username, String passwordHash, String role, String totpSecret,
                    boolean totpEnabled, boolean active, int failedAttempts, Instant lockedUntil) {}

    private static final Duration TOUCH_INTERVAL = Duration.ofSeconds(60);

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totp;
    private final AdminAuditService audit;
    private final RateLimiter rateLimiter;
    private final AppProperties.Admin config;
    private final Clock clock;
    /** Used to equalise timing when the username does not exist. */
    private final String dummyHash;

    public AdminSessionService(JdbcClient jdbc, PasswordEncoder passwordEncoder, TotpService totp,
                               AdminAuditService audit, RateLimiter rateLimiter, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.totp = totp;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.config = props.admin();
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(Hashing.randomToken(16));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult login(String username, String password, String totpCode, String ip, String userAgent) {
        rateLimiter.check("admin-login-ip:" + ip, 20, Duration.ofMinutes(15));
        Instant now = clock.instant();
        AdminRow admin = findByUsername(username).orElse(null);
        if (admin == null || !admin.active()) {
            passwordEncoder.matches(password == null ? "" : password, dummyHash);
            audit.log(null, username, "ADMIN_LOGIN_FAILED", "ADMIN", null, null, Map.of(), ip);
            throw invalidCredentials();
        }
        if (admin.lockedUntil() != null && admin.lockedUntil().isAfter(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED",
                    "Too many failed attempts. Try again later.");
        }
        if (password == null || !passwordEncoder.matches(password, admin.passwordHash())) {
            recordFailure(admin, now, ip);
            throw invalidCredentials();
        }
        if (admin.totpEnabled()) {
            if (totpCode == null || totpCode.isBlank()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "TOTP_REQUIRED", "Enter the code from your authenticator app.");
            }
            if (!totp.verify(admin.totpSecret(), totpCode.trim())) {
                recordFailure(admin, now, ip);
                throw new ApiException(HttpStatus.UNAUTHORIZED, "TOTP_INVALID", "The authenticator code is not correct.");
            }
        }
        jdbc.sql("UPDATE admin_users SET failed_attempts = 0, locked_until = NULL, last_login_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now)).param("id", admin.id()).update();

        String token = Hashing.randomToken(32);
        jdbc.sql("""
                INSERT INTO admin_sessions (id, admin_id, token_hash, ip, user_agent, created_at, last_seen_at, expires_at)
                VALUES (:id, :admin, :hash, :ip, :ua, :now, :now, :exp)
                """)
                .param("id", UUID.randomUUID())
                .param("admin", admin.id())
                .param("hash", Hashing.sha256Hex(token))
                .param("ip", ip)
                .param("ua", userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())))
                .param("now", Timestamp.from(now))
                .param("exp", Timestamp.from(now.plus(config.sessionAbsoluteTimeout())))
                .update();
        audit.log(admin.id(), admin.username(), "ADMIN_LOGIN", "ADMIN", admin.id().toString(), null, Map.of(), ip);
        return new LoginResult(token, view(admin), config.sessionAbsoluteTimeout());
    }

    /** Resolves a session cookie to an admin, enforcing idle and absolute timeouts. */
    public Optional<AdminPrincipal> authenticate(String token, String ip) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        record SessionRow(UUID id, UUID adminId, String username, String role, boolean active, Instant lastSeen,
                          Instant expiresAt, Instant revokedAt) {}
        Optional<SessionRow> row = jdbc.sql("""
                SELECT s.id, s.admin_id, s.last_seen_at, s.expires_at, s.revoked_at, a.username, a.role, a.active
                FROM admin_sessions s JOIN admin_users a ON a.id = s.admin_id
                WHERE s.token_hash = :h
                """)
                .param("h", Hashing.sha256Hex(token))
                .query((rs, n) -> new SessionRow(rs.getObject("id", UUID.class), rs.getObject("admin_id", UUID.class),
                        rs.getString("username"), rs.getString("role"), rs.getBoolean("active"),
                        rs.getTimestamp("last_seen_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()))
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        SessionRow s = row.get();
        if (s.revokedAt() != null || !s.active() || s.expiresAt().isBefore(now)
                || s.lastSeen().plus(config.sessionIdleTimeout()).isBefore(now)) {
            return Optional.empty();
        }
        if (s.lastSeen().plus(TOUCH_INTERVAL).isBefore(now)) {
            jdbc.sql("UPDATE admin_sessions SET last_seen_at = :now WHERE id = :id")
                    .param("now", Timestamp.from(now)).param("id", s.id()).update();
        }
        return Optional.of(new AdminPrincipal(s.adminId(), s.username(), s.role(), ip));
    }

    public void logout(String token) {
        if (token == null) {
            return;
        }
        jdbc.sql("UPDATE admin_sessions SET revoked_at = :now WHERE token_hash = :h AND revoked_at IS NULL")
                .param("now", Timestamp.from(clock.instant())).param("h", Hashing.sha256Hex(token)).update();
    }

    public void revokeAll(UUID adminId) {
        jdbc.sql("UPDATE admin_sessions SET revoked_at = :now WHERE admin_id = :id AND revoked_at IS NULL")
                .param("now", Timestamp.from(clock.instant())).param("id", adminId).update();
    }

    public AdminView view(UUID adminId) {
        return view(findById(adminId).orElseThrow(() -> ApiException.notFound("Admin not found")));
    }

    Optional<AdminRow> findById(UUID id) {
        return jdbc.sql("SELECT * FROM admin_users WHERE id = :id").param("id", id).query(this::map).optional();
    }

    Optional<AdminRow> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT * FROM admin_users WHERE lower(username) = lower(:u)").param("u", username.trim())
                .query(this::map).optional();
    }

    private void recordFailure(AdminRow admin, Instant now, String ip) {
        int failed = admin.failedAttempts() + 1;
        boolean lock = failed >= config.maxFailedLogins();
        jdbc.sql("UPDATE admin_users SET failed_attempts = :f, locked_until = :l WHERE id = :id")
                .param("f", lock ? 0 : failed)
                .param("l", lock ? Timestamp.from(now.plus(config.lockout())) : null)
                .param("id", admin.id())
                .update();
        audit.log(admin.id(), admin.username(), lock ? "ADMIN_LOCKED" : "ADMIN_LOGIN_FAILED", "ADMIN",
                admin.id().toString(), null, Map.of(), ip);
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("INVALID_CREDENTIALS", "Wrong username or password.");
    }

    private static AdminView view(AdminRow a) {
        return new AdminView(a.id(), a.username(), a.role(), a.totpEnabled());
    }

    private AdminRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp locked = rs.getTimestamp("locked_until");
        return new AdminRow(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("role"), rs.getString("totp_secret"), rs.getBoolean("totp_enabled"),
                rs.getBoolean("active"), rs.getInt("failed_attempts"), locked == null ? null : locked.toInstant());
    }
}
