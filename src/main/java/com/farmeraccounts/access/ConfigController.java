package com.farmeraccounts.access;

import com.farmeraccounts.access.AppConfig.AppStatus;
import com.farmeraccounts.auth.FarmerPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {

    private final AppConfigService appConfig;
    private final Clock clock;

    public ConfigController(AppConfigService appConfig, Clock clock) {
        this.appConfig = appConfig;
        this.clock = clock;
    }

    /** Unauthenticated remote configuration. Always answers, even while suspended. */
    @GetMapping("/api/public/config")
    public Map<String, Object> publicConfig() {
        return describe(appConfig.current());
    }

    /**
     * Authenticated access check. Reaching this method means the access guard accepted the
     * request, so the client may renew its offline authorisation lease until {@code leaseExpiresAt}.
     */
    @GetMapping("/api/me/access")
    public Map<String, Object> access(FarmerPrincipal farmer) {
        AppConfig c = appConfig.current();
        Instant now = clock.instant();
        Map<String, Object> body = describe(c);
        body.put("accountStatus", "ACTIVE");
        body.put("leaseExpiresAt", now.plusSeconds(c.offlineGracePeriodHours() * 3600L).toString());
        return body;
    }

    private Map<String, Object> describe(AppConfig c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appStatus", c.appStatus().name());
        String messageEn = "";
        String messageKn = "";
        if (c.appStatus() == AppStatus.SUSPENDED) {
            messageEn = c.suspensionMessageEn();
            messageKn = c.suspensionMessageKn();
        } else if (c.appStatus() == AppStatus.MAINTENANCE) {
            messageEn = c.maintenanceMessageEn();
            messageKn = c.maintenanceMessageKn();
        }
        body.put("maintenanceMessage", messageEn);
        body.put("messageEn", messageEn);
        body.put("messageKn", messageKn);
        body.put("minimumSupportedVersion", c.minimumSupportedVersion());
        body.put("forceUpdate", c.forceUpdate());
        body.put("offlineGracePeriodHours", c.offlineGracePeriodHours());
        body.put("serverTime", clock.instant().toString());
        return body;
    }
}
