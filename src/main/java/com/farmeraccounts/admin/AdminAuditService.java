package com.farmeraccounts.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Append-only log of every administrative action (enforced by a database trigger). */
@Service
public class AdminAuditService {

    public record AuditEntry(long id, UUID adminId, String adminUsername, String action, String targetType,
                             String targetId, String reason, String details, String ip, Instant createdAt) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public AdminAuditService(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public void log(AdminPrincipal admin, String action, String targetType, String targetId, String reason,
                    Map<String, ?> details) {
        log(admin == null ? null : admin.id(), admin == null ? null : admin.username(), action, targetType, targetId,
                reason, details, admin == null ? null : admin.ip());
    }

    public void log(UUID adminId, String username, String action, String targetType, String targetId, String reason,
                    Map<String, ?> details, String ip) {
        jdbc.sql("""
                INSERT INTO admin_audit_logs (admin_id, admin_username, action, target_type, target_id, reason, details, ip, created_at)
                VALUES (:admin, :username, :action, :tt, :tid, :reason, CAST(:details AS jsonb), :ip, :now)
                """)
                .param("admin", adminId)
                .param("username", username)
                .param("action", action)
                .param("tt", targetType)
                .param("tid", targetId)
                .param("reason", reason)
                .param("details", details == null || details.isEmpty() ? null : json.writeValueAsString(details))
                .param("ip", ip)
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    public List<AuditEntry> list(String action, String targetId, int limit, int offset) {
        return jdbc.sql("""
                SELECT * FROM admin_audit_logs
                WHERE (CAST(:action AS text) IS NULL OR action = :action)
                  AND (CAST(:target AS text) IS NULL OR target_id = :target)
                ORDER BY id DESC LIMIT :limit OFFSET :offset
                """)
                .param("action", action)
                .param("target", targetId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> new AuditEntry(rs.getLong("id"), rs.getObject("admin_id", UUID.class),
                        rs.getString("admin_username"), rs.getString("action"), rs.getString("target_type"),
                        rs.getString("target_id"), rs.getString("reason"), rs.getString("details"), rs.getString("ip"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
