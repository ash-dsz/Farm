package com.farmeraccounts.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All application settings, bound from the {@code app.*} namespace (see application.yml). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Security security, Jwt jwt, Otp otp, Login login, Prices prices, Admin admin) {

    public record Security(boolean requireHttps) {}

    public record Jwt(String secret, String issuer, Duration accessTokenTtl, Duration refreshTokenTtl) {}

    public record Otp(
            boolean enabled,
            String provider,
            String hmacSecret,
            int length,
            Duration ttl,
            int maxVerifyAttempts,
            int maxSendsPerPhoneWindow,
            Duration sendWindow,
            int maxSendsPerPhoneDay,
            int maxSendsPerIpHour,
            boolean testModeEnabled,
            String testNumbers,
            String messageTemplate,
            Http http) {

        public record Http(String url, String method, String headers, String contentType, String bodyTemplate) {}
    }

    public record Login(int maxFailedAttempts, Duration lockout) {}

    public record Prices(String apiKey, String url, String state, Duration cacheTtl) {}

    public record Admin(
            boolean cookieSecure,
            Duration sessionIdleTimeout,
            Duration sessionAbsoluteTimeout,
            int maxFailedLogins,
            Duration lockout,
            String bootstrapUsername,
            String bootstrapPassword) {}
}
