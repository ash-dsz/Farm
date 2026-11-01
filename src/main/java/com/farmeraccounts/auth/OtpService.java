package com.farmeraccounts.auth;

import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.common.Hashing;
import com.farmeraccounts.common.RateLimiter;
import com.farmeraccounts.config.AppProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues and verifies OTPs. Codes are stored only as HMACs and every path is rate limited. */
@Service
public class OtpService {

    public record OtpRequestResult(long expiresInSeconds, long resendAfterSeconds) {}

    private static final Duration RESEND_DELAY = Duration.ofSeconds(30);

    private final JdbcClient jdbc;
    private final AppProperties.Otp config;
    private final OtpProvider provider;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final Map<String, String> testNumbers;

    public OtpService(JdbcClient jdbc, AppProperties props, OtpProvider provider, RateLimiter rateLimiter, Clock clock) {
        this.jdbc = jdbc;
        this.config = props.otp();
        this.provider = provider;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.testNumbers = config.testModeEnabled() ? parseTestNumbers(config.testNumbers()) : Map.of();
    }

    @Transactional
    public OtpRequestResult request(String phone, String ip) {
        rateLimiter.check("otp-ip:" + ip, config.maxSendsPerIpHour(), Duration.ofHours(1));
        Instant now = clock.instant();

        Instant last = jdbc.sql("SELECT max(created_at) FROM otp_challenges WHERE phone = :p")
                .param("p", phone).query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
        if (last != null && last.plus(RESEND_DELAY).isAfter(now)) {
            throw ApiException.tooManyRequests("Please wait a few seconds before requesting another OTP.");
        }
        int inWindow = countSince(phone, now.minus(config.sendWindow()));
        int inDay = countSince(phone, now.minus(Duration.ofDays(1)));
        if (inWindow >= config.maxSendsPerPhoneWindow() || inDay >= config.maxSendsPerPhoneDay()) {
            throw ApiException.tooManyRequests("Too many OTP requests for this number. Please try again later.");
        }

        String testCode = testCodeFor(phone);
        String code = testCode != null ? testCode : Hashing.randomDigits(config.length());
        jdbc.sql("""
                INSERT INTO otp_challenges (id, phone, code_hash, expires_at, request_ip, created_at)
                VALUES (:id, :phone, :hash, :exp, :ip, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("phone", phone)
                .param("hash", hash(phone, code))
                .param("exp", Timestamp.from(now.plus(config.ttl())))
                .param("ip", ip)
                .param("now", Timestamp.from(now))
                .update();
        if (testCode == null) {
            provider.send(phone, code, config.messageTemplate().replace("{code}", code));
        }
        return new OtpRequestResult(config.ttl().toSeconds(), RESEND_DELAY.toSeconds());
    }

    /**
     * Verifies the latest open challenge for the number. Wrong codes count against the challenge,
     * which is burnt after {@code maxVerifyAttempts}.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void verify(String phone, String code, String ip) {
        rateLimiter.check("otp-verify-ip:" + ip, 60, Duration.ofHours(1));
        Instant now = clock.instant();
        var challenge = jdbc.sql("""
                SELECT id, code_hash, attempts FROM otp_challenges
                WHERE phone = :p AND consumed_at IS NULL AND expires_at > :now
                ORDER BY created_at DESC LIMIT 1 FOR UPDATE
                """)
                .param("p", phone)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("code_hash"), rs.getInt("attempts")})
                .optional()
                .orElseThrow(() -> ApiException.badRequest("OTP_EXPIRED", "The OTP has expired. Please request a new one."));
        UUID id = (UUID) challenge[0];
        int attempts = (int) challenge[2];
        if (attempts >= config.maxVerifyAttempts()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_ATTEMPTS_EXCEEDED",
                    "Too many wrong attempts. Please request a new OTP.");
        }
        String expected = (String) challenge[1];
        if (code == null || !Hashing.constantTimeEquals(expected, hash(phone, code.trim()))) {
            jdbc.sql("UPDATE otp_challenges SET attempts = attempts + 1 WHERE id = :id").param("id", id).update();
            throw ApiException.badRequest("OTP_INVALID", "The OTP is not correct.");
        }
        jdbc.sql("UPDATE otp_challenges SET consumed_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now)).param("id", id).update();
    }

    /** Test numbers are only loaded in test mode; a key ending in '*' matches by prefix. */
    private String testCodeFor(String phone) {
        String exact = testNumbers.get(phone);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> e : testNumbers.entrySet()) {
            String key = e.getKey();
            if (key.endsWith("*") && phone.startsWith(key.substring(0, key.length() - 1))) {
                return e.getValue();
            }
        }
        return null;
    }

    private int countSince(String phone, Instant since) {
        return jdbc.sql("SELECT count(*) FROM otp_challenges WHERE phone = :p AND created_at > :since")
                .param("p", phone).param("since", Timestamp.from(since)).query(Integer.class).single();
    }

    private String hash(String phone, String code) {
        return Hashing.hmacSha256Hex(config.hmacSecret(), phone + ":" + code);
    }

    private static Map<String, String> parseTestNumbers(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null) {
            return map;
        }
        for (String pair : raw.split(",")) {
            String[] kv = pair.trim().split("=");
            if (kv.length == 2 && kv[1].trim().matches("\\d{4,8}")) {
                map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return Map.copyOf(map);
    }
}
