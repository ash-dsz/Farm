package com.farmeraccounts.access;

import com.farmeraccounts.access.AppConfig.AppStatus;
import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.common.Versions;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Reads and updates the single-row remote configuration that holds the global kill switch.
 *
 * <p>The value is cached for at most {@link #CACHE_TTL} so that every protected request can be
 * checked cheaply; updates made through this service invalidate the cache immediately, and other
 * instances pick the change up within the TTL.
 */
@Service
public class AppConfigService {

    static final Duration CACHE_TTL = Duration.ofSeconds(3);

    private record Cached(AppConfig config, long loadedAt) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private volatile Cached cached;

    public AppConfigService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public AppConfig current() {
        Cached c = cached;
        long now = clock.millis();
        if (c == null || now - c.loadedAt > CACHE_TTL.toMillis()) {
            c = new Cached(load(), now);
            cached = c;
        }
        return c.config;
    }

    public void invalidate() {
        cached = null;
    }

    public AppConfig load() {
        return jdbc.sql("SELECT * FROM app_configuration WHERE id = 1")
                .query((rs, n) -> new AppConfig(
                        AppStatus.valueOf(rs.getString("app_status")),
                        rs.getString("suspension_message_kn"),
                        rs.getString("suspension_message_en"),
                        rs.getString("maintenance_message_kn"),
                        rs.getString("maintenance_message_en"),
                        rs.getString("minimum_supported_version"),
                        rs.getBoolean("force_update"),
                        rs.getInt("offline_grace_period_hours"),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("updated_by", UUID.class)))
                .single();
    }

    /** Persists a new configuration; callers are responsible for authorisation and auditing. */
    public AppConfig save(AppConfig c, UUID adminId) {
        jdbc.sql("""
                UPDATE app_configuration SET app_status = :status,
                    suspension_message_kn = :sKn, suspension_message_en = :sEn,
                    maintenance_message_kn = :mKn, maintenance_message_en = :mEn,
                    minimum_supported_version = :minVersion, force_update = :forceUpdate,
                    offline_grace_period_hours = :grace, updated_at = :now, updated_by = :admin
                WHERE id = 1
                """)
                .param("status", c.appStatus().name())
                .param("sKn", c.suspensionMessageKn())
                .param("sEn", c.suspensionMessageEn())
                .param("mKn", c.maintenanceMessageKn())
                .param("mEn", c.maintenanceMessageEn())
                .param("minVersion", c.minimumSupportedVersion())
                .param("forceUpdate", c.forceUpdate())
                .param("grace", c.offlineGracePeriodHours())
                .param("now", Timestamp.from(clock.instant()))
                .param("admin", adminId)
                .update();
        invalidate();
        return current();
    }

    /** Throws if the platform is suspended or under maintenance. */
    public void assertServiceAvailable() {
        AppConfig c = current();
        if (c.appStatus() == AppStatus.SUSPENDED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "APP_SUSPENDED", c.suspensionMessageEn(),
                    Map.of("messageKn", c.suspensionMessageKn(), "messageEn", c.suspensionMessageEn()));
        }
        if (c.appStatus() == AppStatus.MAINTENANCE) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APP_MAINTENANCE", c.maintenanceMessageEn(),
                    Map.of("messageKn", c.maintenanceMessageKn(), "messageEn", c.maintenanceMessageEn()));
        }
    }

    /** Throws UPGRADE_REQUIRED when a forced update is configured and the client is older. */
    public void assertVersionSupported(String appVersion) {
        AppConfig c = current();
        if (c.forceUpdate() && Versions.compare(appVersion, c.minimumSupportedVersion()) < 0) {
            throw new ApiException(HttpStatus.UPGRADE_REQUIRED, "UPGRADE_REQUIRED",
                    "Please update the application to continue",
                    Map.of("minimumSupportedVersion", c.minimumSupportedVersion()));
        }
    }
}
