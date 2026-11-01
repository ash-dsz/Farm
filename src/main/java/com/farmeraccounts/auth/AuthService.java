package com.farmeraccounts.auth;

import com.farmeraccounts.access.AppConfigService;
import com.farmeraccounts.access.FarmerAccessService;
import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.common.PhoneNumbers;
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
 * Farmer accounts: phone number + password. Passwords are stored as bcrypt hashes; repeated wrong
 * passwords lock the account for a while. The offline PIN is handled entirely on the phone.
 * OTP login stays available behind {@code app.otp.enabled} for when an SMS provider is set up.
 */
@Service
public class AuthService {

    public static final int MIN_PASSWORD = 6;
    public static final int MAX_PASSWORD = 64;

    public record UserView(UUID id, String phone, String name, String preferredLanguage) {}

    public record AuthResponse(String accessToken, String refreshToken, long expiresIn, UserView user) {}

    private record UserRow(UUID id, String phone, String name, String passwordHash, int failedAttempts,
                           Instant lockedUntil, String status, String language) {}

    private final JdbcClient jdbc;
    private final OtpService otp;
    private final TokenService tokens;
    private final AppConfigService appConfig;
    private final FarmerAccessService access;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final AppProperties props;
    private final Clock clock;
    /** Used to equalise timing when the phone number is not registered. */
    private final String dummyHash;

    public AuthService(JdbcClient jdbc, OtpService otp, TokenService tokens, AppConfigService appConfig,
                       FarmerAccessService access, PasswordEncoder passwordEncoder, RateLimiter rateLimiter,
                       AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.otp = otp;
        this.tokens = tokens;
        this.appConfig = appConfig;
        this.access = access;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw ApiException.badRequest("WEAK_PASSWORD",
                    "The password must have " + MIN_PASSWORD + " to " + MAX_PASSWORD + " characters.");
        }
    }

    // ------------------------------------------------------------------ phone + password

    @Transactional
    public AuthResponse register(String rawPhone, String password, String deviceId, String ip, String appVersion) {
        gate(appVersion);
        requireDevice(deviceId);
        rateLimiter.check("register-ip:" + ip, 10, Duration.ofHours(1));
        String phone = PhoneNumbers.normalizeIndian(rawPhone);
        validatePassword(password);
        Instant now = clock.instant();
        int inserted = jdbc.sql("""
                INSERT INTO users (id, phone, password_hash, created_at, updated_at)
                VALUES (:id, :phone, :hash, :now, :now)
                ON CONFLICT (phone) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("phone", phone)
                .param("hash", passwordEncoder.encode(password))
                .param("now", Timestamp.from(now))
                .update();
        if (inserted == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PHONE_TAKEN",
                    "An account with this mobile number already exists. Please log in.");
        }
        UserRow user = findByPhone(phone).orElseThrow();
        trustDevice(user.id(), deviceId, now);
        return respond(user, tokens.issue(user.id(), deviceId, false));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse login(String rawPhone, String password, String deviceId, String ip, String appVersion) {
        gate(appVersion);
        requireDevice(deviceId);
        rateLimiter.check("login-ip:" + ip, 30, Duration.ofMinutes(15));
        String phone = PhoneNumbers.normalizeIndian(rawPhone);
        UserRow user = findByPhone(phone).orElse(null);
        Instant now = clock.instant();
        if (user == null || user.passwordHash() == null) {
            passwordEncoder.matches(password == null ? "" : password, dummyHash);
            throw invalidLogin(null);
        }
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            throw loginLocked(user.lockedUntil(), now);
        }
        if (password == null || !passwordEncoder.matches(password, user.passwordHash())) {
            int failed = user.failedAttempts() + 1;
            AppProperties.Login cfg = props.login();
            Instant lockUntil = failed >= cfg.maxFailedAttempts() ? now.plus(cfg.lockout()) : null;
            jdbc.sql("UPDATE users SET login_failed_attempts = :f, login_locked_until = :l WHERE id = :id")
                    .param("f", lockUntil != null ? 0 : failed)
                    .param("l", lockUntil == null ? null : Timestamp.from(lockUntil))
                    .param("id", user.id()).update();
            if (lockUntil != null) {
                throw loginLocked(lockUntil, now);
            }
            throw invalidLogin(cfg.maxFailedAttempts() - failed);
        }
        jdbc.sql("UPDATE users SET login_failed_attempts = 0, login_locked_until = NULL WHERE id = :id")
                .param("id", user.id()).update();
        // The status is revealed only after a correct password.
        if ("BLOCKED".equals(user.status())) {
            throw FarmerAccessService.blocked();
        }
        trustDevice(user.id(), deviceId, now);
        return respond(user, tokens.issue(user.id(), deviceId, false));
    }

    @Transactional
    public void changePassword(FarmerPrincipal farmer, String current, String next) {
        UserRow user = findById(farmer.userId());
        if (user.passwordHash() != null && (current == null || !passwordEncoder.matches(current, user.passwordHash()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "WRONG_PASSWORD", "The current password is not correct.");
        }
        validatePassword(next);
        setPassword(user.id(), next);
    }

    /** Used by the admin "reset password" action; also signs the farmer out of all phones. */
    public void setPassword(UUID userId, String password) {
        validatePassword(password);
        jdbc.sql("""
                UPDATE users SET password_hash = :h, login_failed_attempts = 0, login_locked_until = NULL, updated_at = :now
                WHERE id = :id
                """)
                .param("h", passwordEncoder.encode(password))
                .param("now", Timestamp.from(clock.instant()))
                .param("id", userId)
                .update();
    }

    // ------------------------------------------------------------------ sessions

    public AuthResponse refresh(String refreshToken, String deviceId, String appVersion) {
        gate(appVersion);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw ApiException.unauthorized("SESSION_EXPIRED", "Please log in again");
        }
        access.assertAccountActive(tokens.ownerOf(refreshToken));
        TokenService.RotationResult result = tokens.rotate(refreshToken, deviceId);
        return respond(findById(result.userId()), result.tokens());
    }

    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            tokens.revoke(refreshToken);
        }
    }

    public UserView updateProfile(FarmerPrincipal farmer, String name, String language) {
        String cleanName = name == null || name.isBlank() ? null : name.trim();
        if (cleanName != null && cleanName.length() > 100) {
            throw ApiException.badRequest("INVALID_NAME", "The name is too long.");
        }
        if (language != null && !language.equals("kn") && !language.equals("en")) {
            throw ApiException.badRequest("INVALID_LANGUAGE", "Language must be kn or en.");
        }
        jdbc.sql("""
                UPDATE users SET name = :name, preferred_language = COALESCE(:lang, preferred_language), updated_at = :now
                WHERE id = :id
                """)
                .param("name", cleanName)
                .param("lang", language)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", farmer.userId())
                .update();
        return view(findById(farmer.userId()));
    }

    public UserView me(FarmerPrincipal farmer) {
        return view(findById(farmer.userId()));
    }

    // ------------------------------------------------------------------ OTP (switched off by default)

    public OtpService.OtpRequestResult requestOtp(String rawPhone, String ip, String appVersion) {
        requireOtpEnabled();
        gate(appVersion);
        return otp.request(PhoneNumbers.normalizeIndian(rawPhone), ip);
    }

    /** Logs in (and registers, if new) with a verified OTP. */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse verifyOtp(String rawPhone, String code, String deviceId, String ip, String appVersion) {
        requireOtpEnabled();
        gate(appVersion);
        requireDevice(deviceId);
        String phone = PhoneNumbers.normalizeIndian(rawPhone);
        otp.verify(phone, code, ip);
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO users (id, phone, created_at, updated_at) VALUES (:id, :phone, :now, :now)
                ON CONFLICT (phone) DO NOTHING
                """)
                .param("id", UUID.randomUUID()).param("phone", phone).param("now", Timestamp.from(now)).update();
        UserRow user = findByPhone(phone).orElseThrow();
        if ("BLOCKED".equals(user.status())) {
            throw FarmerAccessService.blocked();
        }
        trustDevice(user.id(), deviceId, now);
        return respond(user, tokens.issue(user.id(), deviceId, true));
    }

    private void requireOtpEnabled() {
        if (!props.otp().enabled()) {
            throw ApiException.notFound("OTP login is not enabled");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void gate(String appVersion) {
        appConfig.assertServiceAvailable();
        appConfig.assertVersionSupported(appVersion);
    }

    private static void requireDevice(String deviceId) {
        if (deviceId == null) {
            throw ApiException.badRequest("DEVICE_ID_REQUIRED", "Missing or invalid device id.");
        }
    }

    private void trustDevice(UUID userId, String deviceId, Instant now) {
        jdbc.sql("""
                INSERT INTO user_devices (user_id, device_id, verified_at, last_login_at) VALUES (:u, :d, :now, :now)
                ON CONFLICT (user_id, device_id) DO UPDATE SET last_login_at = :now
                """)
                .param("u", userId).param("d", deviceId).param("now", Timestamp.from(now)).update();
    }

    private static ApiException invalidLogin(Integer attemptsLeft) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_LOGIN", "Wrong mobile number or password.",
                attemptsLeft == null ? Map.of() : Map.of("attemptsLeft", attemptsLeft));
    }

    private static ApiException loginLocked(Instant until, Instant now) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_LOCKED",
                "Too many wrong passwords. Please try again later.",
                Map.of("retryAfterSeconds", Math.max(1, Duration.between(now, until).toSeconds())));
    }

    private AuthResponse respond(UserRow user, TokenService.Tokens t) {
        return new AuthResponse(t.accessToken(), t.refreshToken(), t.expiresInSeconds(), view(user));
    }

    private static UserView view(UserRow u) {
        return new UserView(u.id(), u.phone(), u.name(), u.language());
    }

    private Optional<UserRow> findByPhone(String phone) {
        return jdbc.sql("SELECT * FROM users WHERE phone = :p").param("p", phone).query(this::mapUser).optional();
    }

    private UserRow findById(UUID id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id").param("id", id).query(this::mapUser).optional()
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHORIZED", "Please log in again"));
    }

    private UserRow mapUser(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp locked = rs.getTimestamp("login_locked_until");
        return new UserRow(rs.getObject("id", UUID.class), rs.getString("phone"), rs.getString("name"),
                rs.getString("password_hash"), rs.getInt("login_failed_attempts"),
                locked == null ? null : locked.toInstant(), rs.getString("status"), rs.getString("preferred_language"));
    }
}
