package com.farmeraccounts.admin;

import com.farmeraccounts.access.AppConfig;
import com.farmeraccounts.access.AppConfig.AppStatus;
import com.farmeraccounts.access.AppConfigService;
import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.common.Versions;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Global kill switch and remote configuration. */
@RestController
@RequestMapping("/api/admin")
public class AdminPlatformController {

    /** Text the admin must type to confirm a global shutdown. */
    public static final String SUSPEND_CONFIRMATION = "SUSPEND";

    public record StatusChange(@NotNull AppStatus status, @NotBlank @Size(max = 1000) String reason,
                               @Size(max = 20) String confirmation) {}

    public record ConfigUpdate(@NotBlank @Size(max = 1000) String suspensionMessageKn,
                               @NotBlank @Size(max = 1000) String suspensionMessageEn,
                               @NotBlank @Size(max = 1000) String maintenanceMessageKn,
                               @NotBlank @Size(max = 1000) String maintenanceMessageEn,
                               @NotBlank @Size(max = 20) String minimumSupportedVersion,
                               boolean forceUpdate,
                               @Min(1) @Max(720) int offlineGracePeriodHours,
                               @Size(max = 1000) String reason) {}

    private final AppConfigService appConfig;
    private final AdminAuditService audit;

    public AdminPlatformController(AppConfigService appConfig, AdminAuditService audit) {
        this.appConfig = appConfig;
        this.audit = audit;
    }

    @GetMapping("/config")
    public AppConfig config() {
        return appConfig.load();
    }

    @PostMapping("/app-status")
    @Transactional
    public AppConfig changeStatus(@AuthenticationPrincipal AdminPrincipal admin, @Validated @RequestBody StatusChange body) {
        if (body.status() == AppStatus.SUSPENDED && !SUSPEND_CONFIRMATION.equals(body.confirmation())) {
            throw ApiException.badRequest("CONFIRMATION_REQUIRED",
                    "Type " + SUSPEND_CONFIRMATION + " to confirm a global shutdown.");
        }
        AppConfig c = appConfig.load();
        AppConfig saved = appConfig.save(new AppConfig(body.status(), c.suspensionMessageKn(), c.suspensionMessageEn(),
                c.maintenanceMessageKn(), c.maintenanceMessageEn(), c.minimumSupportedVersion(), c.forceUpdate(),
                c.offlineGracePeriodHours(), null, null), admin.id());
        audit.log(admin, "APP_STATUS_CHANGE", "APP", "global", body.reason(),
                Map.of("from", c.appStatus().name(), "to", body.status().name()));
        return saved;
    }

    @PutMapping("/config")
    @Transactional
    public AppConfig updateConfig(@AuthenticationPrincipal AdminPrincipal admin, @Validated @RequestBody ConfigUpdate body) {
        if (!Versions.isValid(body.minimumSupportedVersion())) {
            throw ApiException.badRequest("INVALID_VERSION", "Use a version like 1.0.0");
        }
        AppConfig c = appConfig.load();
        AppConfig next = new AppConfig(c.appStatus(), body.suspensionMessageKn().trim(), body.suspensionMessageEn().trim(),
                body.maintenanceMessageKn().trim(), body.maintenanceMessageEn().trim(), body.minimumSupportedVersion(),
                body.forceUpdate(), body.offlineGracePeriodHours(), null, null);
        AppConfig saved = appConfig.save(next, admin.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("minimumSupportedVersion", body.minimumSupportedVersion());
        details.put("forceUpdate", body.forceUpdate());
        details.put("offlineGracePeriodHours", body.offlineGracePeriodHours());
        details.put("messagesChanged", !c.suspensionMessageEn().equals(next.suspensionMessageEn())
                || !c.suspensionMessageKn().equals(next.suspensionMessageKn())
                || !c.maintenanceMessageEn().equals(next.maintenanceMessageEn())
                || !c.maintenanceMessageKn().equals(next.maintenanceMessageKn()));
        audit.log(admin, "CONFIG_UPDATE", "APP", "global", body.reason(), details);
        return saved;
    }
}
