package com.farmeraccounts.admin;

import com.farmeraccounts.common.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {

    public record AdminListItem(UUID id, String username, String role, boolean active, boolean totpEnabled,
                                Instant lastLoginAt, Instant createdAt) {}

    public record TotpSetup(String secret, String otpauthUri) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AdminSessionService sessions;
    private final AdminAuditService audit;
    private final TotpService totp;
    private final Clock clock;

    public AdminUserService(JdbcClient jdbc, PasswordEncoder passwordEncoder, AdminSessionService sessions,
                            AdminAuditService audit, TotpService totp, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.audit = audit;
        this.totp = totp;
        this.clock = clock;
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw ApiException.badRequest("WEAK_PASSWORD", "The password must be 12 to 128 characters long.");
        }
        if (password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit)) {
            throw ApiException.badRequest("WEAK_PASSWORD", "The password must contain letters and numbers.");
        }
    }

    public List<AdminListItem> list() {
        return jdbc.sql("SELECT * FROM admin_users ORDER BY created_at")
                .query((rs, n) -> new AdminListItem(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("role"), rs.getBoolean("active"), rs.getBoolean("totp_enabled"),
                        rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Transactional
    public AdminListItem create(AdminPrincipal actor, String username, String password, String role) {
        if (username == null || !username.trim().matches("[A-Za-z0-9._@-]{3,60}")) {
            throw ApiException.badRequest("INVALID_USERNAME", "Use 3-60 letters, digits or . _ @ -");
        }
        if (!"ADMIN".equals(role) && !"OWNER".equals(role)) {
            throw ApiException.badRequest("INVALID_ROLE", "Role must be ADMIN or OWNER.");
        }
        validatePassword(password);
        if (sessions.findByUsername(username).isPresent()) {
            throw ApiException.badRequest("USERNAME_TAKEN", "This username already exists.");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO admin_users (id, username, password_hash, role) VALUES (:id, :u, :p, :r)")
                .param("id", id).param("u", username.trim()).param("p", passwordEncoder.encode(password))
                .param("r", role).update();
        audit.log(actor, "ADMIN_CREATE", "ADMIN", id.toString(), null, Map.of("username", username.trim(), "role", role));
        return list().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void setActive(AdminPrincipal actor, UUID id, boolean active, String reason) {
        if (actor.id().equals(id) && !active) {
            throw ApiException.badRequest("CANNOT_DEACTIVATE_SELF", "You cannot deactivate your own account.");
        }
        int n = jdbc.sql("UPDATE admin_users SET active = :a, updated_at = :now WHERE id = :id")
                .param("a", active).param("now", Timestamp.from(clock.instant())).param("id", id).update();
        if (n == 0) {
            throw ApiException.notFound("Admin not found");
        }
        if (!active) {
            sessions.revokeAll(id);
        }
        audit.log(actor, active ? "ADMIN_ACTIVATE" : "ADMIN_DEACTIVATE", "ADMIN", id.toString(), reason, Map.of());
    }

    @Transactional
    public void changePassword(AdminPrincipal actor, String current, String next) {
        var admin = sessions.findById(actor.id()).orElseThrow();
        if (current == null || !passwordEncoder.matches(current, admin.passwordHash())) {
            throw ApiException.badRequest("INVALID_CREDENTIALS", "The current password is not correct.");
        }
        validatePassword(next);
        jdbc.sql("UPDATE admin_users SET password_hash = :p, updated_at = :now WHERE id = :id")
                .param("p", passwordEncoder.encode(next)).param("now", Timestamp.from(clock.instant()))
                .param("id", actor.id()).update();
        audit.log(actor, "ADMIN_PASSWORD_CHANGE", "ADMIN", actor.id().toString(), null, Map.of());
    }

    /** Generates a new (not yet enabled) TOTP secret for the current admin. */
    @Transactional
    public TotpSetup setupTotp(AdminPrincipal actor) {
        var admin = sessions.findById(actor.id()).orElseThrow();
        if (admin.totpEnabled()) {
            throw ApiException.badRequest("TOTP_ALREADY_ENABLED", "Two-factor authentication is already on.");
        }
        String secret = totp.newSecret();
        jdbc.sql("UPDATE admin_users SET totp_secret = :s WHERE id = :id").param("s", secret).param("id", actor.id()).update();
        return new TotpSetup(secret, totp.otpauthUri(secret, admin.username()));
    }

    @Transactional
    public void enableTotp(AdminPrincipal actor, String code) {
        var admin = sessions.findById(actor.id()).orElseThrow();
        if (admin.totpSecret() == null || !totp.verify(admin.totpSecret(), code)) {
            throw ApiException.badRequest("TOTP_INVALID", "The authenticator code is not correct.");
        }
        jdbc.sql("UPDATE admin_users SET totp_enabled = TRUE WHERE id = :id").param("id", actor.id()).update();
        audit.log(actor, "ADMIN_TOTP_ENABLE", "ADMIN", actor.id().toString(), null, Map.of());
    }

    @Transactional
    public void disableTotp(AdminPrincipal actor, String password, String code) {
        var admin = sessions.findById(actor.id()).orElseThrow();
        if (password == null || !passwordEncoder.matches(password, admin.passwordHash())
                || !totp.verify(admin.totpSecret(), code)) {
            throw ApiException.badRequest("INVALID_CREDENTIALS", "Password or authenticator code is not correct.");
        }
        jdbc.sql("UPDATE admin_users SET totp_enabled = FALSE, totp_secret = NULL WHERE id = :id")
                .param("id", actor.id()).update();
        audit.log(actor, "ADMIN_TOTP_DISABLE", "ADMIN", actor.id().toString(), null, Map.of());
    }

    Instant now() {
        return clock.instant();
    }
}
