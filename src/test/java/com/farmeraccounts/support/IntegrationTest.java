package com.farmeraccounts.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.farmeraccounts.access.AppConfigService;
import com.farmeraccounts.admin.AdminSessionFilter;
import com.farmeraccounts.common.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
public abstract class IntegrationTest {

    public static final String TEST_OTP = "123456";
    public static final String APP_VERSION = "1.0.0";
    public static final String PASSWORD = "farm-pass-1";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected RateLimiter rateLimiter;
    @Autowired protected AppConfigService appConfig;
    @Autowired protected PasswordEncoder passwordEncoder;

    /** A response: status plus parsed JSON body (null when empty). */
    public record Res(int status, JsonNode body, MvcResult raw) {
        public String code() {
            return body == null || body.get("code") == null ? null : body.get("code").asString();
        }
    }

    /** A logged-in farmer for tests. */
    public record Farmer(UUID id, String phone, String deviceId, String accessToken, String refreshToken) {}

    @AfterEach
    void resetPlatform() {
        jdbc.sql("UPDATE app_configuration SET app_status = 'ACTIVE', force_update = FALSE, minimum_supported_version = '1.0.0'")
                .update();
        appConfig.invalidate();
        rateLimiter.reset();
        clock.reset();
    }

    // ---------------------------------------------------------------- HTTP helpers

    protected Res call(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mvc.perform(req.header("X-App-Version", APP_VERSION)).andReturn();
        String body = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode node = body.isBlank() || !body.trim().startsWith("{") && !body.trim().startsWith("[")
                ? null : json.readTree(body);
        return new Res(r.getResponse().getStatus(), node, r);
    }

    protected MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder req, Object body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    protected Res farmerGet(Farmer f, String url) throws Exception {
        return call(get(url).header("Authorization", "Bearer " + f.accessToken()).header("X-Device-Id", f.deviceId()));
    }

    protected Res farmerPost(Farmer f, String url, Object body) throws Exception {
        return call(withJson(post(url), body).header("Authorization", "Bearer " + f.accessToken())
                .header("X-Device-Id", f.deviceId()));
    }

    protected Res farmerPut(Farmer f, String url, Object body) throws Exception {
        return call(withJson(put(url), body).header("Authorization", "Bearer " + f.accessToken())
                .header("X-Device-Id", f.deviceId()));
    }

    // ---------------------------------------------------------------- farmer helpers

    protected static String randomPhone() {
        return "+91999" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    protected static String randomDevice() {
        return "dev-" + UUID.randomUUID();
    }

    protected Res requestOtp(String phone) throws Exception {
        return call(withJson(post("/api/auth/otp/request"), Map.of("phone", phone)));
    }

    protected Res verifyOtp(String phone, String code, String deviceId) throws Exception {
        return call(withJson(post("/api/auth/otp/verify"), Map.of("phone", phone, "code", code))
                .header("X-Device-Id", deviceId));
    }

    protected Res register(String phone, String password, String deviceId) throws Exception {
        return call(withJson(post("/api/auth/register"), Map.of("phone", phone, "password", password))
                .header("X-Device-Id", deviceId));
    }

    protected Res login(String phone, String password, String deviceId) throws Exception {
        return call(withJson(post("/api/auth/login"), Map.of("phone", phone, "password", password))
                .header("X-Device-Id", deviceId));
    }

    protected Res refresh(String refreshToken, String deviceId) throws Exception {
        return call(withJson(post("/api/auth/refresh"), Map.of("refreshToken", refreshToken))
                .header("X-Device-Id", deviceId));
    }

    /** Registration with phone + password (PASSWORD), plus a profile name. */
    protected Farmer registerFarmer() throws Exception {
        String phone = randomPhone();
        String device = randomDevice();
        Res r = register(phone, PASSWORD, device);
        if (r.status() != 201) {
            throw new AssertionError("Registration failed: " + r.body());
        }
        Farmer f = new Farmer(UUID.fromString(r.body().get("user").get("id").asString()), phone, device,
                r.body().get("accessToken").asString(), r.body().get("refreshToken").asString());
        farmerPut(f, "/api/me/profile", Map.of("name", "Test Farmer", "preferredLanguage", "kn"));
        return f;
    }

    // ---------------------------------------------------------------- sync helpers

    protected static Map<String, Object> op(String type, UUID entityId, Long baseVersion, Map<String, Object> payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opId", UUID.randomUUID().toString());
        m.put("type", type);
        m.put("entityId", entityId.toString());
        m.put("baseVersion", baseVersion);
        m.put("payload", payload);
        return m;
    }

    protected Res push(Farmer f, List<Map<String, Object>> ops) throws Exception {
        return farmerPost(f, "/api/sync/push", Map.of("operations", ops));
    }

    /** Pushes one operation and returns its result node. */
    protected JsonNode pushOne(Farmer f, Map<String, Object> op) throws Exception {
        Res r = push(f, List.of(op));
        if (r.status() != 200) {
            throw new AssertionError("Push failed with HTTP " + r.status() + ": " + r.body());
        }
        return r.body().get("results").get(0);
    }

    protected static Map<String, Object> farm(UUID id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id.toString());
        m.put("name", name);
        m.put("village", "Puttur");
        m.put("district", "Dakshina Kannada");
        m.put("state", "Karnataka");
        m.put("totalAcreage", 4.5);
        m.put("primaryCrop", "ARECANUT");
        m.put("deleted", false);
        return m;
    }

    protected static Map<String, Object> worker(UUID id, UUID farmId, String name, long wagePaise) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id.toString());
        m.put("farmId", farmId.toString());
        m.put("name", name);
        m.put("dailyWagePaise", wagePaise);
        m.put("joiningDate", "2026-01-01");
        m.put("active", true);
        return m;
    }

    protected static Map<String, Object> wageRate(UUID workerId, long wagePaise, String from) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", UUID.randomUUID().toString());
        m.put("workerId", workerId.toString());
        m.put("dailyWagePaise", wagePaise);
        m.put("effectiveFrom", from);
        m.put("createdAt", java.time.Instant.now().toString());
        return m;
    }

    protected static Map<String, Object> attendance(UUID id, UUID farmId, UUID workerId, LocalDate date, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id.toString());
        m.put("farmId", farmId.toString());
        m.put("workerId", workerId.toString());
        m.put("date", date.toString());
        m.put("status", status);
        return m;
    }

    protected static Map<String, Object> txn(UUID id, UUID farmId, UUID workerId, String type, long amountPaise,
                                             String date, UUID reverses, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id.toString());
        m.put("farmId", farmId.toString());
        m.put("workerId", workerId.toString());
        m.put("type", type);
        m.put("amountPaise", amountPaise);
        m.put("date", date);
        m.put("note", note);
        m.put("reversesTransactionId", reverses == null ? null : reverses.toString());
        return m;
    }

    /** A farmer with one farm and one worker (Rs 600/day), already synced. */
    protected record Setup(Farmer farmer, UUID farmId, UUID workerId) {}

    protected Setup setupFarmWithWorker() throws Exception {
        Farmer f = registerFarmer();
        UUID farmId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        List<Map<String, Object>> ops = new ArrayList<>();
        ops.add(op("farm.upsert", farmId, null, farm(farmId, "Main farm")));
        ops.add(op("worker.upsert", workerId, null, worker(workerId, farmId, "Ramesh", 600_00)));
        Map<String, Object> rate = wageRate(workerId, 600_00, "2026-01-01");
        ops.add(op("wageRate.create", UUID.fromString((String) rate.get("id")), null, rate));
        Res r = push(f, ops);
        for (JsonNode result : r.body().get("results")) {
            if (!"APPLIED".equals(result.get("status").asString())) {
                throw new AssertionError("Setup push failed: " + result);
            }
        }
        return new Setup(f, farmId, workerId);
    }

    // ---------------------------------------------------------------- admin helpers

    protected static final String ADMIN_PASSWORD = "Str0ng-admin-passw0rd";

    protected String createAdmin(String role) {
        String username = "admin" + ThreadLocalRandom.current().nextInt(1_000_000);
        jdbc.sql("INSERT INTO admin_users (id, username, password_hash, role) VALUES (:id, :u, :p, :r)")
                .param("id", UUID.randomUUID()).param("u", username)
                .param("p", passwordEncoder.encode(ADMIN_PASSWORD)).param("r", role).update();
        return username;
    }

    protected Cookie adminLogin(String username) throws Exception {
        Res r = call(withJson(post("/api/admin/auth/login"), Map.of("username", username, "password", ADMIN_PASSWORD))
                .header(AdminSessionFilter.CSRF_HEADER, AdminSessionFilter.CSRF_VALUE));
        if (r.status() != 200) {
            throw new AssertionError("Admin login failed: " + r.body());
        }
        return r.raw().getResponse().getCookie("FA_ADMIN_SESSION");
    }

    protected Cookie newAdminSession() throws Exception {
        return adminLogin(createAdmin("OWNER"));
    }

    protected Res adminGet(Cookie session, String url) throws Exception {
        return call(get(url).cookie(session));
    }

    protected Res adminPost(Cookie session, String url, Object body) throws Exception {
        return call(withJson(post(url), body).cookie(session)
                .header(AdminSessionFilter.CSRF_HEADER, AdminSessionFilter.CSRF_VALUE));
    }

    protected Res adminPut(Cookie session, String url, Object body) throws Exception {
        return call(withJson(put(url), body).cookie(session)
                .header(AdminSessionFilter.CSRF_HEADER, AdminSessionFilter.CSRF_VALUE));
    }

    protected Res setAppStatus(Cookie admin, String status) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("reason", "Test " + status);
        body.put("confirmation", "SUSPENDED".equals(status) ? "SUSPEND" : null);
        return adminPost(admin, "/api/admin/app-status", body);
    }
}
