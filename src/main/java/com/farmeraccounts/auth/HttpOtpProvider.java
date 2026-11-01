package com.farmeraccounts.auth;

import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.common.PhoneNumbers;
import com.farmeraccounts.config.AppProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Vendor-neutral SMS gateway client configured entirely through environment variables
 * (URL, method, headers and a body template). Placeholders: {phone} (+91...), {phone10}
 * (10 digits), {phone12} (91...), {code}, {message}. Values are JSON- or URL-escaped to match
 * the configured content type.
 */
@Component
@ConditionalOnProperty(name = "app.otp.provider", havingValue = "http")
public class HttpOtpProvider implements OtpProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpOtpProvider.class);

    private final AppProperties.Otp.Http config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public HttpOtpProvider(AppProperties props) {
        this.config = props.otp().http();
    }

    @Override
    public void send(String phone, String code, String message) {
        boolean json = config.contentType() != null && config.contentType().contains("json");
        String phone10 = phone.substring(3);
        String body = fill(config.bodyTemplate(), phone, phone10, code, message, json);
        String url = fill(config.url(), phone, phone10, code, message, false);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", config.contentType());
        if (config.headers() != null && !config.headers().isBlank()) {
            for (String pair : config.headers().split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    req.header(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
        String method = config.method() == null ? "POST" : config.method().toUpperCase();
        req.method(method, "GET".equals(method)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<Void> res = client.send(req.build(), HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() >= 300) {
                log.error("SMS gateway returned HTTP {} for {}", res.statusCode(), PhoneNumbers.mask(phone));
                throw smsFailed();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw smsFailed();
        } catch (java.io.IOException e) {
            log.error("SMS gateway unreachable for {}: {}", PhoneNumbers.mask(phone), e.getClass().getSimpleName());
            throw smsFailed();
        }
    }

    private static ApiException smsFailed() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "OTP_SEND_FAILED", "Could not send the OTP. Please try again.");
    }

    private static String fill(String template, String phone, String phone10, String code, String message, boolean json) {
        return template
                .replace("{phone12}", esc("91" + phone10, json))
                .replace("{phone10}", esc(phone10, json))
                .replace("{phone}", esc(phone, json))
                .replace("{code}", esc(code, json))
                .replace("{message}", esc(message, json));
    }

    private static String esc(String v, boolean json) {
        if (json) {
            return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
