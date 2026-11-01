package com.farmeraccounts.admin;

import com.farmeraccounts.config.AppProperties;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first OWNER from ADMIN_BOOTSTRAP_USERNAME / ADMIN_BOOTSTRAP_PASSWORD, and only when
 * no admin exists yet. Once an admin exists these variables are ignored and should be removed.
 * There is no default or hardcoded admin account.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuditService audit;
    private final AppProperties.Admin config;

    public AdminBootstrap(JdbcClient jdbc, PasswordEncoder passwordEncoder, AdminAuditService audit, AppProperties props) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.config = props.admin();
    }

    @Override
    public void run(ApplicationArguments args) {
        int admins = jdbc.sql("SELECT count(*) FROM admin_users").query(Integer.class).single();
        String username = config.bootstrapUsername();
        String password = config.bootstrapPassword();
        if (admins > 0) {
            if (username != null && !username.isBlank()) {
                log.warn("ADMIN_BOOTSTRAP_* is set but admins already exist; ignoring. Remove these variables.");
            }
            return;
        }
        if (username == null || username.isBlank() || password == null) {
            log.warn("No admin account exists. Set ADMIN_BOOTSTRAP_USERNAME and ADMIN_BOOTSTRAP_PASSWORD "
                    + "for the first start to create the platform owner.");
            return;
        }
        AdminUserService.validatePassword(password);
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO admin_users (id, username, password_hash, role) VALUES (:id, :u, :p, 'OWNER')")
                .param("id", id).param("u", username.trim()).param("p", passwordEncoder.encode(password)).update();
        audit.log(id, username.trim(), "ADMIN_BOOTSTRAP", "ADMIN", id.toString(), "Initial owner account", Map.of(), null);
        log.info("Created the initial OWNER admin account '{}'.", username.trim());
    }
}
