package com.farmeraccounts.auth;

import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.common.Hashing;
import com.farmeraccounts.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short-lived JWT access tokens plus rotating opaque refresh tokens (stored hashed). */
@Service
public class TokenService {

    public static final String CLAIM_TYPE = "typ";
    public static final String CLAIM_DEVICE = "dev";
    public static final String CLAIM_OTP_AT = "otp_at";
    public static final String FARMER_TYPE = "farmer";

    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {}

    private record RefreshRow(UUID id, UUID userId, String deviceId, Instant expiresAt, Instant revokedAt, UUID replacedBy) {}

    private final JwtEncoder encoder;
    private final JdbcClient jdbc;
    private final AppProperties.Jwt config;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JdbcClient jdbc, AppProperties props, Clock clock) {
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.config = props.jwt();
        this.clock = clock;
    }

    public static SecretKey signingKey(AppProperties props) {
        return new SecretKeySpec(props.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Transactional
    public Tokens issue(UUID userId, String deviceId, boolean otpVerified) {
        String access = accessToken(userId, deviceId, otpVerified);
        String refresh = Hashing.randomToken(32);
        insertRefresh(UUID.randomUUID(), userId, deviceId, refresh);
        return new Tokens(access, refresh, config.accessTokenTtl().toSeconds());
    }

    /**
     * Exchanges a refresh token for a new pair. A refresh token can be used once; presenting an
     * already-rotated token revokes every token of that device (likely theft).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public RotationResult rotate(String refreshToken, String deviceId) {
        Instant now = clock.instant();
        RefreshRow row = jdbc.sql("""
                SELECT id, user_id, device_id, expires_at, revoked_at, replaced_by
                FROM refresh_tokens WHERE token_hash = :h FOR UPDATE
                """)
                .param("h", Hashing.sha256Hex(refreshToken))
                .query((rs, n) -> new RefreshRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("device_id"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                        rs.getObject("replaced_by", UUID.class)))
                .optional()
                .orElseThrow(TokenService::invalidSession);
        if (row.revokedAt() != null) {
            if (row.replacedBy() != null) {
                revokeDevice(row.userId(), row.deviceId());
            }
            throw invalidSession();
        }
        if (row.expiresAt().isBefore(now) || (deviceId != null && !deviceId.equals(row.deviceId()))) {
            throw invalidSession();
        }
        String newRefresh = Hashing.randomToken(32);
        UUID newId = UUID.randomUUID();
        insertRefresh(newId, row.userId(), row.deviceId(), newRefresh);
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = :now, replaced_by = :next WHERE id = :id")
                .param("now", Timestamp.from(now)).param("next", newId).param("id", row.id()).update();
        String access = accessToken(row.userId(), row.deviceId(), false);
        return new RotationResult(row.userId(), new Tokens(access, newRefresh, config.accessTokenTtl().toSeconds()));
    }

    public record RotationResult(UUID userId, Tokens tokens) {}

    /** Looks up the owner of a refresh token without consuming it (used to check status first). */
    public UUID ownerOf(String refreshToken) {
        return jdbc.sql("SELECT user_id FROM refresh_tokens WHERE token_hash = :h")
                .param("h", Hashing.sha256Hex(refreshToken))
                .query(UUID.class).optional().orElseThrow(TokenService::invalidSession);
    }

    public void revoke(String refreshToken) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = :now WHERE token_hash = :h AND revoked_at IS NULL")
                .param("now", Timestamp.from(clock.instant()))
                .param("h", Hashing.sha256Hex(refreshToken))
                .update();
    }

    public void revokeDevice(UUID userId, String deviceId) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = :now WHERE user_id = :u AND device_id = :d AND revoked_at IS NULL")
                .param("now", Timestamp.from(clock.instant())).param("u", userId).param("d", deviceId).update();
    }

    private String accessToken(UUID userId, String deviceId, boolean otpVerified) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(config.issuer())
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(config.accessTokenTtl()))
                .claim(CLAIM_TYPE, FARMER_TYPE)
                .claim(CLAIM_DEVICE, deviceId);
        if (otpVerified) {
            claims.claim(CLAIM_OTP_AT, now.getEpochSecond());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private void insertRefresh(UUID id, UUID userId, String deviceId, String token) {
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO refresh_tokens (id, user_id, device_id, token_hash, expires_at, created_at)
                VALUES (:id, :u, :d, :h, :exp, :now)
                """)
                .param("id", id).param("u", userId).param("d", deviceId)
                .param("h", Hashing.sha256Hex(token))
                .param("exp", Timestamp.from(now.plus(config.refreshTokenTtl())))
                .param("now", Timestamp.from(now))
                .update();
    }

    private static ApiException invalidSession() {
        return ApiException.unauthorized("SESSION_EXPIRED", "Please log in again");
    }
}
