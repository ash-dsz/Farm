package com.farmeraccounts.access;

import com.farmeraccounts.common.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Decides whether a farmer may use authenticated backend services. Consulted on every protected
 * request (never cached), so blocking takes effect immediately for all of the farmer's tokens.
 */
@Service
public class FarmerAccessService {

    public static final String BLOCKED_MESSAGE_KN =
            "ನಿಮ್ಮ ಖಾತೆಯನ್ನು ನಿರ್ವಾಹಕರು ತಡೆಹಿಡಿದಿದ್ದಾರೆ. ಸಹಾಯಕ್ಕಾಗಿ ಬೆಂಬಲ ತಂಡವನ್ನು ಸಂಪರ್ಕಿಸಿ.";
    public static final String BLOCKED_MESSAGE_EN =
            "Your account has been blocked by the administrator. Please contact support.";

    private static final Duration ACTIVITY_WRITE_INTERVAL = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final AppConfigService appConfig;
    private final Clock clock;

    public FarmerAccessService(JdbcClient jdbc, AppConfigService appConfig, Clock clock) {
        this.jdbc = jdbc;
        this.appConfig = appConfig;
        this.clock = clock;
    }

    /** Full gate for an authenticated farmer request: platform status, account status, app version. */
    public void assertFarmerAllowed(UUID userId, String appVersion) {
        appConfig.assertServiceAvailable();
        assertAccountActive(userId);
        appConfig.assertVersionSupported(appVersion);
    }

    public void assertAccountActive(UUID userId) {
        String status = jdbc.sql("SELECT status FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHORIZED", "Please log in again"));
        if ("BLOCKED".equals(status)) {
            throw blocked();
        }
    }

    public static ApiException blocked() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED", BLOCKED_MESSAGE_EN,
                Map.of("messageKn", BLOCKED_MESSAGE_KN, "messageEn", BLOCKED_MESSAGE_EN));
    }

    /** Records activity for admin statistics, writing at most once per interval per farmer. */
    public void touchActivity(UUID userId) {
        Timestamp now = Timestamp.from(clock.instant());
        Timestamp threshold = Timestamp.from(clock.instant().minus(ACTIVITY_WRITE_INTERVAL));
        jdbc.sql("UPDATE users SET last_active_at = :now WHERE id = :id "
                        + "AND (last_active_at IS NULL OR last_active_at < :threshold)")
                .param("now", now)
                .param("id", userId)
                .param("threshold", threshold)
                .update();
    }
}
