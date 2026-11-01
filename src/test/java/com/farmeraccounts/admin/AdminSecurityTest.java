package com.farmeraccounts.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.farmeraccounts.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class AdminSecurityTest extends IntegrationTest {

    @Autowired TotpService totp;

    private Res adminLoginAttempt(String username, String password, String totpCode) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("username", username, "password", password));
        if (totpCode != null) {
            body.put("totpCode", totpCode);
        }
        return call(withJson(post("/api/admin/auth/login"), body)
                .header(AdminSessionFilter.CSRF_HEADER, AdminSessionFilter.CSRF_VALUE));
    }

    @Test
    void adminEndpointsRequireASession() throws Exception {
        assertThat(call(get("/api/admin/dashboard")).status()).isEqualTo(401);
        assertThat(call(get("/api/admin/farmers")).status()).isEqualTo(401);
        assertThat(call(get("/api/admin/dashboard").cookie(new Cookie(AdminSessionService.COOKIE, "forged"))).status())
                .isEqualTo(401);
    }

    @Test
    void loginSetsAHardenedCookieAndPasswordsAreHashed() throws Exception {
        String username = createAdmin("ADMIN");
        Res r = adminLoginAttempt(username, ADMIN_PASSWORD, null);
        assertThat(r.status()).isEqualTo(200);
        String setCookie = r.raw().getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/admin");

        String hash = jdbc.sql("SELECT password_hash FROM admin_users WHERE username = :u").param("u", username)
                .query(String.class).single();
        assertThat(hash).startsWith("$2").doesNotContain(ADMIN_PASSWORD);
        String tokenHash = jdbc.sql("SELECT s.token_hash FROM admin_sessions s JOIN admin_users a ON a.id = s.admin_id "
                + "WHERE a.username = :u").param("u", username).query(String.class).single();
        assertThat(setCookie).doesNotContain(tokenHash);
    }

    @Test
    void stateChangingRequestsNeedTheCsrfHeader() throws Exception {
        Cookie session = newAdminSession();
        Res r = call(withJson(post("/api/admin/app-status"),
                Map.of("status", "SUSPENDED", "reason", "x", "confirmation", "SUSPEND")).cookie(session));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("CSRF_CHECK_FAILED");
    }

    @Test
    void wrongPasswordsLockTheAccount() throws Exception {
        String username = createAdmin("ADMIN");
        for (int i = 0; i < 5; i++) {
            assertThat(adminLoginAttempt(username, "wrong-password-123", null).code()).isEqualTo("INVALID_CREDENTIALS");
        }
        assertThat(adminLoginAttempt(username, ADMIN_PASSWORD, null).code()).isEqualTo("ACCOUNT_LOCKED");
        clock.advance(Duration.ofMinutes(16));
        assertThat(adminLoginAttempt(username, ADMIN_PASSWORD, null).status()).isEqualTo(200);

        long failures = jdbc.sql("SELECT count(*) FROM admin_audit_logs WHERE admin_username = :u AND action LIKE 'ADMIN_%'")
                .param("u", username).query(Long.class).single();
        assertThat(failures).isGreaterThanOrEqualTo(5);
    }

    @Test
    void unknownUsernameLooksTheSameAsWrongPassword() throws Exception {
        assertThat(adminLoginAttempt("nobody-here", "whatever-123456", null).code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void sessionsExpireWhenIdleAndOnLogout() throws Exception {
        Cookie session = newAdminSession();
        assertThat(adminGet(session, "/api/admin/dashboard").status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(31));
        assertThat(adminGet(session, "/api/admin/dashboard").status()).isEqualTo(401);

        Cookie second = newAdminSession();
        assertThat(adminPost(second, "/api/admin/auth/logout", Map.of()).status()).isEqualTo(204);
        assertThat(adminGet(second, "/api/admin/dashboard").status()).isEqualTo(401);
    }

    @Test
    void sessionsHaveAnAbsoluteLifetime() throws Exception {
        Cookie session = newAdminSession();
        for (int i = 0; i < 25; i++) {
            clock.advance(Duration.ofMinutes(29));
            if (i < 24) {
                assertThat(adminGet(session, "/api/admin/dashboard").status()).isEqualTo(200);
            }
        }
        // 12h25m after login, despite constant activity
        assertThat(adminGet(session, "/api/admin/dashboard").status()).isEqualTo(401);
    }

    @Test
    void optionalTwoFactorAuthentication() throws Exception {
        String username = createAdmin("ADMIN");
        Cookie session = adminLogin(username);
        JsonNode setup = adminPost(session, "/api/admin/auth/totp/setup", Map.of()).body();
        String secret = setup.get("secret").asString();
        assertThat(setup.get("otpauthUri").asString()).startsWith("otpauth://totp/");

        assertThat(adminPost(session, "/api/admin/auth/totp/enable", Map.of("code", "000000")).code()).isEqualTo("TOTP_INVALID");
        assertThat(adminPost(session, "/api/admin/auth/totp/enable", Map.of("code", totp.currentCode(secret))).status())
                .isEqualTo(204);

        assertThat(adminLoginAttempt(username, ADMIN_PASSWORD, null).code()).isEqualTo("TOTP_REQUIRED");
        assertThat(adminLoginAttempt(username, ADMIN_PASSWORD, "123123").code()).isIn("TOTP_INVALID");
        assertThat(adminLoginAttempt(username, ADMIN_PASSWORD, totp.currentCode(secret)).status()).isEqualTo(200);
    }

    @Test
    void onlyOwnersManageAdministrators() throws Exception {
        Cookie plainAdmin = adminLogin(createAdmin("ADMIN"));
        Map<String, Object> newAdmin = Map.of("username", "helper" + System.nanoTime() % 100000,
                "password", "Another-strong-pass1", "role", "ADMIN");
        assertThat(adminPost(plainAdmin, "/api/admin/admins", newAdmin).status()).isEqualTo(403);

        Cookie owner = newAdminSession();
        assertThat(adminPost(owner, "/api/admin/admins", newAdmin).status()).isEqualTo(200);
        assertThat(adminPost(owner, "/api/admin/admins",
                Map.of("username", "weak" + System.nanoTime() % 100000, "password", "short", "role", "ADMIN")).code())
                .isEqualTo("WEAK_PASSWORD");
    }

    @Test
    void auditLogCannotBeAlteredOrDeleted() {
        jdbc.sql("INSERT INTO admin_audit_logs (action) VALUES ('TEST')").update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("DELETE FROM admin_audit_logs WHERE action = 'TEST'").update()).hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("UPDATE admin_audit_logs SET reason = 'x' WHERE action = 'TEST'").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void dashboardShowsStatisticsAndStatus() throws Exception {
        setupFarmWithWorker();
        JsonNode body = adminGet(newAdminSession(), "/api/admin/dashboard").body();
        assertThat(body.get("stats").get("totalFarmers").asLong()).isPositive();
        assertThat(body.get("stats").get("totalWorkers").asLong()).isPositive();
        assertThat(body.get("stats").has("syncFailuresLast7Days")).isTrue();
        assertThat(body.get("config").get("appStatus").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void remoteConfigurationIsValidatedAndAudited() throws Exception {
        Cookie session = newAdminSession();
        Map<String, Object> config = new java.util.HashMap<>(Map.of(
                "suspensionMessageKn", "ಸೇವೆ ಸ್ಥಗಿತ", "suspensionMessageEn", "Suspended for review",
                "maintenanceMessageKn", "ನಿರ್ವಹಣೆ", "maintenanceMessageEn", "Maintenance",
                "minimumSupportedVersion", "1.0.0", "forceUpdate", false, "offlineGracePeriodHours", 48));
        assertThat(adminPut(session, "/api/admin/config", config).status()).isEqualTo(200);
        assertThat(call(get("/api/public/config")).body().get("offlineGracePeriodHours").asInt()).isEqualTo(48);

        config.put("offlineGracePeriodHours", 0);
        assertThat(adminPut(session, "/api/admin/config", config).status()).isEqualTo(400);
        config.put("offlineGracePeriodHours", 24);
        config.put("minimumSupportedVersion", "latest");
        assertThat(adminPut(session, "/api/admin/config", config).code()).isEqualTo("INVALID_VERSION");

        // restore the default for other tests
        config.put("minimumSupportedVersion", "1.0.0");
        adminPut(session, "/api/admin/config", config);
    }

    @Test
    void csvExportEscapesFormulas() throws Exception {
        Setup s = setupFarmWithWorker();
        java.util.UUID expenseId = java.util.UUID.randomUUID();
        Map<String, Object> expense = new java.util.LinkedHashMap<>(Map.of(
                "id", expenseId.toString(), "farmId", s.farmId().toString(), "category", "OTHER",
                "description", "=cmd|' /C calc'!A0", "amountPaise", 100_00,
                "date", java.time.LocalDate.now().toString(), "deleted", false));
        pushOne(s.farmer(), op("expense.upsert", expenseId, null, expense));

        Cookie session = newAdminSession();
        Res csv = adminGet(session, "/api/admin/exports/expenses?farmerId=" + s.farmer().id());
        String text = csv.raw().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).contains("\"'=cmd|' /C calc'!A0\"").contains("100.00");

        Res ledger = adminGet(session, "/api/admin/exports/wage-ledger?farmerId=" + s.farmer().id());
        assertThat(ledger.raw().getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("Ramesh");
        assertThat(adminGet(session, "/api/admin/exports/nope").status()).isEqualTo(404);
    }
}
