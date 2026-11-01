package com.farmeraccounts.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.farmeraccounts.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Global suspension, maintenance, individual blocking and recovery, all enforced server-side. */
class ShutdownTest extends IntegrationTest {

    private long rowCount(String table, UUID farmerId) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE farmer_id = :f").param("f", farmerId)
                .query(Long.class).single();
    }

    private JsonNode attendancePush(Setup s) throws Exception {
        UUID id = UUID.randomUUID();
        Res r = push(s.farmer(), java.util.List.of(op("attendance.upsert", id, null,
                attendance(id, s.farmId(), s.workerId(), LocalDate.now(), "PRESENT"))));
        return r.body();
    }

    // ------------------------------------------------------------------ global suspension

    @Test
    void globalSuspensionRequiresExplicitConfirmation() throws Exception {
        Cookie admin = newAdminSession();
        Res r = adminPost(admin, "/api/admin/app-status", Map.of("status", "SUSPENDED", "reason", "test"));
        assertThat(r.code()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(appConfig.load().appStatus()).isEqualTo(AppConfig.AppStatus.ACTIVE);

        Res noReason = adminPost(admin, "/api/admin/app-status", Map.of("status", "SUSPENDED", "confirmation", "SUSPEND"));
        assertThat(noReason.status()).isEqualTo(400);
    }

    @Test
    void suspensionBlocksEverythingAndKeepsAllData() throws Exception {
        Setup s = setupFarmWithWorker();
        Cookie admin = newAdminSession();
        long workersBefore = rowCount("workers", s.farmer().id());
        long ratesBefore = rowCount("wage_rates", s.farmer().id());

        assertThat(setAppStatus(admin, "SUSPENDED").status()).isEqualTo(200);

        // Authenticated API: rejected with the configurable bilingual message
        Res me = farmerGet(s.farmer(), "/api/me");
        assertThat(me.status()).isEqualTo(403);
        assertThat(me.code()).isEqualTo("APP_SUSPENDED");
        assertThat(me.body().get("details").get("messageKn").asString()).contains("ಸ್ಥಗಿತ");
        assertThat(me.body().get("details").get("messageEn").asString()).contains("suspended");

        // No attendance, no financial sync, no data access
        assertThat(farmerPost(s.farmer(), "/api/sync/push", Map.of("operations", java.util.List.of())).code())
                .isEqualTo("APP_SUSPENDED");
        assertThat(attendancePush(s).get("code").asString()).isEqualTo("APP_SUSPENDED");
        assertThat(farmerGet(s.farmer(), "/api/sync/pull").code()).isEqualTo("APP_SUSPENDED");
        assertThat(farmerGet(s.farmer(), "/api/workers").code()).isEqualTo("APP_SUSPENDED");
        assertThat(farmerGet(s.farmer(), "/api/workers/" + s.workerId() + "/ledger").code()).isEqualTo("APP_SUSPENDED");

        // No new logins of any kind (restarting the app or calling the backend directly does not help)
        assertThat(requestOtp(randomPhone()).code()).isEqualTo("APP_SUSPENDED");
        assertThat(verifyOtp(s.farmer().phone(), TEST_OTP, s.farmer().deviceId()).code()).isEqualTo("APP_SUSPENDED");
        assertThat(login(s.farmer().phone(), PASSWORD, s.farmer().deviceId()).code()).isEqualTo("APP_SUSPENDED");
        assertThat(register(randomPhone(), PASSWORD, randomDevice()).code()).isEqualTo("APP_SUSPENDED");
        assertThat(refresh(s.farmer().refreshToken(), s.farmer().deviceId()).code()).isEqualTo("APP_SUSPENDED");

        // The public configuration still answers so the app can show the message
        JsonNode config = call(get("/api/public/config")).body();
        assertThat(config.get("appStatus").asString()).isEqualTo("SUSPENDED");
        assertThat(config.get("messageKn").asString()).isNotBlank();

        // Nothing was deleted, and the owner can still export everything
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE id = :id").param("id", s.farmer().id())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(rowCount("workers", s.farmer().id())).isEqualTo(workersBefore);
        assertThat(rowCount("wage_rates", s.farmer().id())).isEqualTo(ratesBefore);
        Res export = adminGet(admin, "/api/admin/exports/workers?farmerId=" + s.farmer().id());
        assertThat(export.status()).isEqualTo(200);
        assertThat(export.raw().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("Ramesh");
    }

    @Test
    void reactivationRestoresAccessWithoutLoggingInAgain() throws Exception {
        Setup s = setupFarmWithWorker();
        Cookie admin = newAdminSession();
        setAppStatus(admin, "SUSPENDED");
        assertThat(farmerGet(s.farmer(), "/api/me").status()).isEqualTo(403);

        assertThat(setAppStatus(admin, "ACTIVE").status()).isEqualTo(200);

        // The very same tokens work again: no reinstall, no new registration
        assertThat(farmerGet(s.farmer(), "/api/me").status()).isEqualTo(200);
        assertThat(attendancePush(s).get("results").get(0).get("status").asString()).isEqualTo("APPLIED");
        assertThat(refresh(s.farmer().refreshToken(), s.farmer().deviceId()).status()).isEqualTo(200);
        assertThat(login(s.farmer().phone(), PASSWORD, s.farmer().deviceId()).status()).isEqualTo(200);
        assertThat(farmerGet(s.farmer(), "/api/workers").body()).hasSize(1);
    }

    @Test
    void maintenanceModeRejectsWithServiceUnavailable() throws Exception {
        Setup s = setupFarmWithWorker();
        Cookie admin = newAdminSession();
        setAppStatus(admin, "MAINTENANCE");

        Res me = farmerGet(s.farmer(), "/api/me");
        assertThat(me.status()).isEqualTo(503);
        assertThat(me.code()).isEqualTo("APP_MAINTENANCE");
        assertThat(attendancePush(s).get("code").asString()).isEqualTo("APP_MAINTENANCE");
        assertThat(login(s.farmer().phone(), PASSWORD, s.farmer().deviceId()).code()).isEqualTo("APP_MAINTENANCE");

        setAppStatus(admin, "ACTIVE");
        assertThat(farmerGet(s.farmer(), "/api/me").status()).isEqualTo(200);
    }

    @Test
    void statusChangesAreAudited() throws Exception {
        String username = createAdmin("OWNER");
        Cookie admin = adminLogin(username);
        setAppStatus(admin, "SUSPENDED");
        setAppStatus(admin, "ACTIVE");

        JsonNode logs = adminGet(admin, "/api/admin/audit-logs?action=APP_STATUS_CHANGE").body();
        JsonNode suspend = null;
        for (JsonNode entry : logs) {
            if (username.equals(entry.get("adminUsername").asString()) && entry.get("details").asString().contains("SUSPENDED")) {
                suspend = entry;
            }
        }
        assertThat(suspend).isNotNull();
        assertThat(suspend.get("reason").asString()).isEqualTo("Test SUSPENDED");
        assertThat(suspend.get("adminId").asString()).isNotBlank();
        assertThat(suspend.get("createdAt").asString()).isNotBlank();
    }

    @Test
    void anOfflineGraceLeaseIsIssuedOnlyWhileAccessIsAllowed() throws Exception {
        Setup s = setupFarmWithWorker();
        Res access = farmerGet(s.farmer(), "/api/me/access");
        assertThat(access.status()).isEqualTo(200);
        assertThat(access.body().get("offlineGracePeriodHours").asInt()).isEqualTo(24);
        java.time.Instant lease = java.time.Instant.parse(access.body().get("leaseExpiresAt").asString());
        assertThat(lease).isAfter(clock.instant().plus(Duration.ofHours(23)));

        setAppStatus(newAdminSession(), "SUSPENDED");
        assertThat(farmerGet(s.farmer(), "/api/me/access").code()).isEqualTo("APP_SUSPENDED");
    }

    // ------------------------------------------------------------------ individual blocking

    @Test
    void blockedFarmerLosesAllBackendAccessImmediately() throws Exception {
        Setup s = setupFarmWithWorker();
        Setup other = setupFarmWithWorker();
        Cookie admin = newAdminSession();

        Res blocked = adminPost(admin, "/api/admin/farmers/" + s.farmer().id() + "/block",
                Map.of("reason", "Payment dispute"));
        assertThat(blocked.status()).isEqualTo(200);
        assertThat(blocked.body().get("status").asString()).isEqualTo("BLOCKED");
        assertThat(blocked.body().get("blockReason").asString()).isEqualTo("Payment dispute");

        // Existing, still-valid access token is refused
        Res me = farmerGet(s.farmer(), "/api/me");
        assertThat(me.status()).isEqualTo(403);
        assertThat(me.code()).isEqualTo("ACCOUNT_BLOCKED");
        assertThat(attendancePush(s).get("code").asString()).isEqualTo("ACCOUNT_BLOCKED");
        assertThat(farmerGet(s.farmer(), "/api/sync/pull").code()).isEqualTo("ACCOUNT_BLOCKED");

        // Restarting the app / logging in again does not help
        assertThat(refresh(s.farmer().refreshToken(), s.farmer().deviceId()).code()).isEqualTo("ACCOUNT_BLOCKED");
        assertThat(login(s.farmer().phone(), PASSWORD, s.farmer().deviceId()).code()).isEqualTo("ACCOUNT_BLOCKED");
        assertThat(login(s.farmer().phone(), PASSWORD, randomDevice()).code()).isEqualTo("ACCOUNT_BLOCKED");
        clock.advance(Duration.ofSeconds(31));
        requestOtp(s.farmer().phone());
        assertThat(verifyOtp(s.farmer().phone(), TEST_OTP, randomDevice()).code()).isEqualTo("ACCOUNT_BLOCKED");

        // Other farmers are unaffected
        assertThat(farmerGet(other.farmer(), "/api/me").status()).isEqualTo(200);

        // Blocking never deletes data
        assertThat(rowCount("workers", s.farmer().id())).isEqualTo(1);
    }

    @Test
    void wrongPasswordDoesNotRevealBlockedStatus() throws Exception {
        Setup s = setupFarmWithWorker();
        adminPost(newAdminSession(), "/api/admin/farmers/" + s.farmer().id() + "/block", Map.of("reason", "x"));
        assertThat(login(s.farmer().phone(), "wrong-password", s.farmer().deviceId()).code()).isEqualTo("INVALID_LOGIN");
    }

    @Test
    void unblockingRestoresAccessWithTheSameTokens() throws Exception {
        Setup s = setupFarmWithWorker();
        Cookie admin = newAdminSession();
        adminPost(admin, "/api/admin/farmers/" + s.farmer().id() + "/block", Map.of("reason", "Check"));
        assertThat(farmerGet(s.farmer(), "/api/me").status()).isEqualTo(403);

        Res unblocked = adminPost(admin, "/api/admin/farmers/" + s.farmer().id() + "/unblock", Map.of("reason", "Resolved"));
        assertThat(unblocked.body().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(farmerGet(s.farmer(), "/api/me").status()).isEqualTo(200);
        assertThat(attendancePush(s).get("results").get(0).get("status").asString()).isEqualTo("APPLIED");

        JsonNode audit = unblocked.body().get("auditLog");
        assertThat(audit.toString()).contains("FARMER_BLOCK").contains("FARMER_UNBLOCK").contains("Resolved");
    }

    @Test
    void blockingNeedsAReason() throws Exception {
        Setup s = setupFarmWithWorker();
        Res r = adminPost(newAdminSession(), "/api/admin/farmers/" + s.farmer().id() + "/block", Map.of("reason", " "));
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void adminCanSearchFarmersByPhoneAndName() throws Exception {
        Setup s = setupFarmWithWorker();
        Cookie admin = newAdminSession();
        JsonNode byPhone = adminGet(admin, "/api/admin/farmers?q=" + s.farmer().phone().substring(3)).body();
        assertThat(byPhone.get("total").asInt()).isEqualTo(1);
        assertThat(byPhone.get("items").get(0).get("workers").asInt()).isEqualTo(1);
        assertThat(adminGet(admin, "/api/admin/farmers?q=farmer").body().get("total").asInt()).isPositive();
    }

    // ------------------------------------------------------------------ forced update

    @Test
    void forcedUpdateRejectsOldClients() throws Exception {
        Setup s = setupFarmWithWorker();
        jdbc.sql("UPDATE app_configuration SET force_update = TRUE, minimum_supported_version = '2.0.0'").update();
        appConfig.invalidate();
        Res r = farmerGet(s.farmer(), "/api/me");
        assertThat(r.status()).isEqualTo(426);
        assertThat(r.code()).isEqualTo("UPGRADE_REQUIRED");
    }

    // ------------------------------------------------------------------ separation of admin and farmer auth

    @Test
    void farmerTokensCannotReachAdminEndpoints() throws Exception {
        Setup s = setupFarmWithWorker();
        assertThat(farmerGet(s.farmer(), "/api/admin/dashboard").status()).isEqualTo(401);
        assertThat(farmerPost(s.farmer(), "/api/admin/app-status",
                Map.of("status", "SUSPENDED", "reason", "x", "confirmation", "SUSPEND")).status()).isEqualTo(403);
        assertThat(farmerGet(s.farmer(), "/api/admin/exports/workers").status()).isEqualTo(401);
        assertThat(appConfig.load().appStatus()).isEqualTo(AppConfig.AppStatus.ACTIVE);
    }

    @Test
    void adminSessionCannotUseTheFarmerApi() throws Exception {
        Cookie admin = newAdminSession();
        assertThat(call(get("/api/me").cookie(admin)).status()).isEqualTo(401);
    }
}
