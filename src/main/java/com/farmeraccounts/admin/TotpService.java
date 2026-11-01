package com.farmeraccounts.admin;

import com.farmeraccounts.common.Hashing;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base32;
import org.springframework.stereotype.Service;

/** RFC 6238 time-based one-time passwords (30 s step, 6 digits, SHA-1) for optional admin 2FA. */
@Service
public class TotpService {

    private static final int STEP_SECONDS = 30;
    private final Clock clock;

    public TotpService(Clock clock) {
        this.clock = clock;
    }

    public String newSecret() {
        byte[] bytes = new byte[20];
        Hashing.random().nextBytes(bytes);
        return new Base32().encodeToString(bytes).replace("=", "");
    }

    public String otpauthUri(String secret, String username) {
        String label = URLEncoder.encode("Farmer Accounts Admin:" + username, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=Farmer%20Accounts&digits=6&period=30";
    }

    /** Accepts the current code and one step either side to allow for clock drift. */
    public boolean verify(String secret, String code) {
        if (secret == null || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        long counter = clock.instant().getEpochSecond() / STEP_SECONDS;
        for (long c = counter - 1; c <= counter + 1; c++) {
            if (Hashing.constantTimeEquals(generate(secret, c), code)) {
                return true;
            }
        }
        return false;
    }

    String generate(String secret, long counter) {
        try {
            byte[] key = new Base32().decode(secret);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String currentCode(String secret) {
        return generate(secret, clock.instant().getEpochSecond() / STEP_SECONDS);
    }
}
