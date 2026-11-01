package com.farmeraccounts.admin;

import com.farmeraccounts.access.AppConfigService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {

    private final JdbcClient jdbc;
    private final AppConfigService appConfig;
    private final AdminAuditService audit;
    private final Clock clock;

    public AdminDashboardController(JdbcClient jdbc, AppConfigService appConfig, AdminAuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.appConfig = appConfig;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Timestamp weekAgo = Timestamp.from(clock.instant().minus(Duration.ofDays(7)));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalFarmers", count("SELECT count(*) FROM users", null));
        stats.put("activeFarmers", count("SELECT count(*) FROM users WHERE status = 'ACTIVE'", null));
        stats.put("blockedFarmers", count("SELECT count(*) FROM users WHERE status = 'BLOCKED'", null));
        stats.put("farmersActiveLast7Days", count("SELECT count(*) FROM users WHERE last_active_at > :since", weekAgo));
        stats.put("totalWorkers", count("SELECT count(*) FROM workers", null));
        stats.put("totalAttendanceEntries", count("SELECT count(*) FROM attendance", null));
        stats.put("syncFailuresLast7Days",
                count("SELECT count(*) FROM sync_operations WHERE result_status = 'REJECTED' AND created_at > :since", weekAgo));
        stats.put("syncFailuresTotal", count("SELECT count(*) FROM sync_operations WHERE result_status = 'REJECTED'", null));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stats", stats);
        body.put("config", appConfig.load());
        return body;
    }

    @GetMapping("/audit-logs")
    public List<AdminAuditService.AuditEntry> auditLogs(@RequestParam(required = false) String action,
                                                        @RequestParam(required = false) String targetId,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "50") int size) {
        int pageSize = Math.max(1, Math.min(size, 200));
        return audit.list(blankToNull(action), blankToNull(targetId), pageSize, Math.max(0, page) * pageSize);
    }

    private long count(String sql, Timestamp since) {
        var spec = jdbc.sql(sql);
        if (since != null) {
            spec = spec.param("since", since);
        }
        return spec.query(Long.class).single();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
