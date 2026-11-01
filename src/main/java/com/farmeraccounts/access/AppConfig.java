package com.farmeraccounts.access;

import java.time.Instant;
import java.util.UUID;

public record AppConfig(
        AppStatus appStatus,
        String suspensionMessageKn,
        String suspensionMessageEn,
        String maintenanceMessageKn,
        String maintenanceMessageEn,
        String minimumSupportedVersion,
        boolean forceUpdate,
        int offlineGracePeriodHours,
        Instant updatedAt,
        UUID updatedBy) {

    public enum AppStatus {
        ACTIVE,
        MAINTENANCE,
        SUSPENDED
    }
}
