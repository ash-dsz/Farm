package com.farmeraccounts.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails fast on insecure or incomplete configuration instead of running with weak defaults. */
@Component
public class StartupValidator {

    public StartupValidator(AppProperties props, Environment env) {
        requireSecret("app.jwt.secret (JWT_SECRET)", props.jwt().secret());
        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
        if (!props.otp().enabled()) {
            return;
        }
        requireSecret("app.otp.hmac-secret (OTP_HMAC_SECRET)", props.otp().hmacSecret());
        String provider = props.otp().provider();
        if (!"console".equals(provider) && !"http".equals(provider)) {
            throw new IllegalStateException("app.otp.provider must be 'console' or 'http', was: " + provider);
        }
        if ("http".equals(provider) && isBlank(props.otp().http().url())) {
            throw new IllegalStateException("OTP_HTTP_URL is required when OTP_PROVIDER=http");
        }
        if (prod && props.otp().testModeEnabled()) {
            throw new IllegalStateException("OTP test mode must not be enabled in the prod profile");
        }
        if (prod && "console".equals(provider)) {
            throw new IllegalStateException("The console OTP provider must not be used in the prod profile");
        }
    }

    private static void requireSecret(String name, String value) {
        if (isBlank(value) || value.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(name + " must be set and at least 32 bytes long");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
