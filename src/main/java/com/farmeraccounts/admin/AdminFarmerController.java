package com.farmeraccounts.admin;

import com.farmeraccounts.auth.AuthService;
import com.farmeraccounts.common.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Farmer search, details and individual blocking. Blocking never deletes or changes farm data. */
@RestController
@RequestMapping("/api/admin/farmers")
public class AdminFarmerController {

    public record FarmerRow(UUID id, String name, String phone, String status, Instant createdAt, Instant lastActiveAt,
                            int workers) {}

    public record FarmerPage(List<FarmerRow> items, int total, int page, int size) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    private final JdbcClient jdbc;
    private final AdminAuditService audit;
    private final AuthService auth;
    private final Clock clock;

    public AdminFarmerController(JdbcClient jdbc, AdminAuditService audit, AuthService auth, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.auth = auth;
        this.clock = clock;
    }

    @GetMapping
    public FarmerPage search(@RequestParam(required = false) String q,
                             @RequestParam(required = false) String status,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "25") int size) {
        int pageSize = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page) * pageSize;
        String query = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase().replace("%", "") + "%";
        String st = "ACTIVE".equals(status) || "BLOCKED".equals(status) ? status : null;
        String where = """
                WHERE (CAST(:q AS text) IS NULL OR lower(coalesce(u.name, '')) LIKE :q OR u.phone LIKE :q)
                  AND (CAST(:st AS text) IS NULL OR u.status = :st)
                """;
        int total = jdbc.sql("SELECT count(*) FROM users u " + where)
                .param("q", query).param("st", st).query(Integer.class).single();
        List<FarmerRow> rows = jdbc.sql("""
                SELECT u.id, u.name, u.phone, u.status, u.created_at, u.last_active_at,
                       (SELECT count(*) FROM workers w WHERE w.farmer_id = u.id) AS workers
                FROM users u
                """ + where + " ORDER BY u.created_at DESC LIMIT :limit OFFSET :offset")
                .param("q", query).param("st", st).param("limit", pageSize).param("offset", offset)
                .query((rs, n) -> new FarmerRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("phone"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_active_at") == null ? null : rs.getTimestamp("last_active_at").toInstant(),
                        rs.getInt("workers")))
                .list();
        return new FarmerPage(rows, total, Math.max(0, page), pageSize);
    }

    @GetMapping("/{id}")
    public Map<String, Object> details(@PathVariable UUID id) {
        Map<String, Object> farmer = jdbc.sql("""
                SELECT id, name, phone, status, block_reason, blocked_at, preferred_language, created_at, last_active_at,
                       (password_hash IS NOT NULL) AS password_set
                FROM users WHERE id = :id
                """)
                .param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("name", rs.getString("name"));
                    m.put("phone", rs.getString("phone"));
                    m.put("status", rs.getString("status"));
                    m.put("blockReason", rs.getString("block_reason"));
                    m.put("blockedAt", instant(rs.getTimestamp("blocked_at")));
                    m.put("preferredLanguage", rs.getString("preferred_language"));
                    m.put("createdAt", instant(rs.getTimestamp("created_at")));
                    m.put("lastActiveAt", instant(rs.getTimestamp("last_active_at")));
                    m.put("passwordSet", rs.getBoolean("password_set"));
                    return m;
                })
                .optional()
                .orElseThrow(() -> ApiException.notFound("Farmer not found"));

        farmer.put("farms", jdbc.sql("""
                SELECT id, name, village, district, state, total_acreage, primary_crop, deleted_at IS NOT NULL AS deleted
                FROM farms WHERE farmer_id = :id ORDER BY created_at
                """)
                .param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("name", rs.getString("name"));
                    m.put("village", rs.getString("village"));
                    m.put("district", rs.getString("district"));
                    m.put("state", rs.getString("state"));
                    m.put("totalAcreage", rs.getBigDecimal("total_acreage"));
                    m.put("primaryCrop", rs.getString("primary_crop"));
                    m.put("deleted", rs.getBoolean("deleted"));
                    return m;
                }).list());

        Map<String, Object> counts = new LinkedHashMap<>();
        for (String table : List.of("workers", "attendance", "worker_transactions", "expenses", "sales")) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table + " WHERE farmer_id = :id")
                    .param("id", id).query(Long.class).single());
        }
        farmer.put("counts", counts);

        farmer.put("devices", jdbc.sql("""
                SELECT device_id, app_version, last_seen_at, last_push_at, last_pull_at, ops_applied, ops_failed, last_error
                FROM synchronization_metadata WHERE farmer_id = :id ORDER BY last_seen_at DESC NULLS LAST
                """)
                .param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("deviceId", rs.getString("device_id"));
                    m.put("appVersion", rs.getString("app_version"));
                    m.put("lastSeenAt", instant(rs.getTimestamp("last_seen_at")));
                    m.put("lastPushAt", instant(rs.getTimestamp("last_push_at")));
                    m.put("lastPullAt", instant(rs.getTimestamp("last_pull_at")));
                    m.put("opsApplied", rs.getLong("ops_applied"));
                    m.put("opsFailed", rs.getLong("ops_failed"));
                    m.put("lastError", rs.getString("last_error"));
                    return m;
                }).list());

        farmer.put("auditLog", audit.list(null, id.toString(), 50, 0));
        return farmer;
    }

    @PostMapping("/{id}/block")
    @Transactional
    public Map<String, Object> block(@AuthenticationPrincipal AdminPrincipal admin, @PathVariable UUID id,
                                     @Validated @RequestBody ReasonRequest body) {
        int n = jdbc.sql("""
                UPDATE users SET status = 'BLOCKED', block_reason = :r, blocked_at = :now, updated_at = :now
                WHERE id = :id
                """)
                .param("r", body.reason().trim()).param("now", Timestamp.from(clock.instant())).param("id", id).update();
        if (n == 0) {
            throw ApiException.notFound("Farmer not found");
        }
        audit.log(admin, "FARMER_BLOCK", "FARMER", id.toString(), body.reason().trim(), Map.of());
        return details(id);
    }

    @PostMapping("/{id}/unblock")
    @Transactional
    public Map<String, Object> unblock(@AuthenticationPrincipal AdminPrincipal admin, @PathVariable UUID id,
                                       @Validated @RequestBody ReasonRequest body) {
        int n = jdbc.sql("""
                UPDATE users SET status = 'ACTIVE', block_reason = NULL, blocked_at = NULL, updated_at = :now
                WHERE id = :id
                """)
                .param("now", Timestamp.from(clock.instant())).param("id", id).update();
        if (n == 0) {
            throw ApiException.notFound("Farmer not found");
        }
        audit.log(admin, "FARMER_UNBLOCK", "FARMER", id.toString(), body.reason().trim(), Map.of());
        return details(id);
    }

    /**
     * Sets a new password for a farmer who forgot theirs (there is no OTP reset), signs the farmer
     * out on every phone and records who did it.
     */
    @PostMapping("/{id}/reset-password")
    @Transactional
    public Map<String, Object> resetPassword(@AuthenticationPrincipal AdminPrincipal admin, @PathVariable UUID id,
                                             @Validated @RequestBody PasswordReset body) {
        if (jdbc.sql("SELECT count(*) FROM users WHERE id = :id").param("id", id).query(Integer.class).single() == 0) {
            throw ApiException.notFound("Farmer not found");
        }
        auth.setPassword(id, body.newPassword());
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = :now WHERE user_id = :id AND revoked_at IS NULL")
                .param("now", Timestamp.from(clock.instant())).param("id", id).update();
        audit.log(admin, "FARMER_PASSWORD_RESET", "FARMER", id.toString(), body.reason().trim(), Map.of());
        return details(id);
    }

    public record PasswordReset(@NotBlank @Size(max = 100) String newPassword, @NotBlank @Size(max = 1000) String reason) {}

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
