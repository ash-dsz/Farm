package com.farmeraccounts.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.farmeraccounts.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthFlowTest extends IntegrationTest {

    // ------------------------------------------------------------------ phone + password

    @Test
    void registerThenLogInOnAnotherPhone() throws Exception {
        String phone = randomPhone();
        Res reg = register(phone.substring(3), PASSWORD, randomDevice()); // 10 digits are normalised to +91
        assertThat(reg.status()).isEqualTo(201);
        assertThat(reg.body().get("accessToken").asString()).isNotBlank();
        assertThat(reg.body().get("user").get("phone").asString()).isEqualTo(phone);

        Res login = login(phone, PASSWORD, randomDevice());
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().get("refreshToken").asString()).isNotBlank();

        // Passwords are stored as bcrypt hashes only
        String hash = jdbc.sql("SELECT password_hash FROM users WHERE phone = :p").param("p", phone).query(String.class).single();
        assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
    }

    @Test
    void aNumberCanOnlyBeRegisteredOnce() throws Exception {
        Farmer f = registerFarmer();
        assertThat(register(f.phone(), "another-pass", randomDevice()).code()).isEqualTo("PHONE_TAKEN");
    }

    @Test
    void weakPasswordsAndInvalidNumbersAreRejected() throws Exception {
        assertThat(register(randomPhone(), "12345", randomDevice()).code()).isEqualTo("WEAK_PASSWORD");
        assertThat(register("12345", PASSWORD, randomDevice()).code()).isEqualTo("INVALID_PHONE");
        assertThat(register("+15551234567", PASSWORD, randomDevice()).code()).isEqualTo("INVALID_PHONE");
        assertThat(register("5123456789", PASSWORD, randomDevice()).code()).isEqualTo("INVALID_PHONE");
    }

    @Test
    void wrongPasswordAndUnknownNumberLookTheSame() throws Exception {
        Farmer f = registerFarmer();
        Res wrong = login(f.phone(), "not-my-password", f.deviceId());
        assertThat(wrong.code()).isEqualTo("INVALID_LOGIN");
        assertThat(wrong.body().get("details").get("attemptsLeft").asInt()).isEqualTo(4);
        assertThat(login(randomPhone(), PASSWORD, f.deviceId()).code()).isEqualTo("INVALID_LOGIN");
    }

    @Test
    void repeatedWrongPasswordsLockTheAccountTemporarily() throws Exception {
        Farmer f = registerFarmer();
        for (int i = 0; i < 4; i++) {
            assertThat(login(f.phone(), "wrong-pass", f.deviceId()).code()).isEqualTo("INVALID_LOGIN");
        }
        assertThat(login(f.phone(), "wrong-pass", f.deviceId()).code()).isEqualTo("LOGIN_LOCKED");
        assertThat(login(f.phone(), PASSWORD, f.deviceId()).code()).isEqualTo("LOGIN_LOCKED");
        clock.advance(Duration.ofMinutes(16));
        assertThat(login(f.phone(), PASSWORD, f.deviceId()).status()).isEqualTo(200);
    }

    @Test
    void passwordChangeNeedsTheCurrentPassword() throws Exception {
        Farmer f = registerFarmer();
        assertThat(farmerPut(f, "/api/me/password", Map.of("currentPassword", "wrong-one", "newPassword", "new-pass-22")).code())
                .isEqualTo("WRONG_PASSWORD");
        assertThat(farmerPut(f, "/api/me/password", Map.of("currentPassword", PASSWORD, "newPassword", "new-pass-22")).status())
                .isEqualTo(204);
        assertThat(login(f.phone(), PASSWORD, f.deviceId()).code()).isEqualTo("INVALID_LOGIN");
        assertThat(login(f.phone(), "new-pass-22", f.deviceId()).status()).isEqualTo(200);
    }

    @Test
    void adminCanResetAForgottenPasswordAndItSignsTheFarmerOut() throws Exception {
        Farmer f = registerFarmer();
        Cookie admin = newAdminSession();
        assertThat(adminPost(admin, "/api/admin/farmers/" + f.id() + "/reset-password",
                Map.of("newPassword", "123", "reason", "forgot")).code()).isEqualTo("WEAK_PASSWORD");
        Res reset = adminPost(admin, "/api/admin/farmers/" + f.id() + "/reset-password",
                Map.of("newPassword", "temp-pass-9", "reason", "Farmer forgot password"));
        assertThat(reset.status()).isEqualTo(200);
        assertThat(refresh(f.refreshToken(), f.deviceId()).status()).isEqualTo(401);
        assertThat(login(f.phone(), "temp-pass-9", f.deviceId()).status()).isEqualTo(200);
        assertThat(reset.body().get("auditLog").toString()).contains("FARMER_PASSWORD_RESET");
    }

    @Test
    void profileNameIsOptional() throws Exception {
        Farmer f = registerFarmer();
        Res profile = farmerPut(f, "/api/me/profile", Map.of("name", "ಶಂಕರ ಭಟ್", "preferredLanguage", "kn"));
        assertThat(profile.body().get("name").asString()).isEqualTo("ಶಂಕರ ಭಟ್");
        assertThat(farmerGet(f, "/api/me").body().get("phone").asString()).isEqualTo(f.phone());
    }

    // ------------------------------------------------------------------ sessions

    @Test
    void refreshTokensRotateAndReuseRevokesTheDevice() throws Exception {
        Farmer f = registerFarmer();
        Res first = refresh(f.refreshToken(), f.deviceId());
        assertThat(first.status()).isEqualTo(200);
        String second = first.body().get("refreshToken").asString();
        assertThat(second).isNotEqualTo(f.refreshToken());
        assertThat(refresh(f.refreshToken(), f.deviceId()).status()).isEqualTo(401);
        assertThat(refresh(second, f.deviceId()).status()).isEqualTo(401);
    }

    @Test
    void refreshTokenIsBoundToItsDevice() throws Exception {
        Farmer f = registerFarmer();
        assertThat(refresh(f.refreshToken(), randomDevice()).status()).isEqualTo(401);
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        Farmer f = registerFarmer();
        assertThat(call(withJson(post("/api/auth/logout"), Map.of("refreshToken", f.refreshToken()))).status()).isEqualTo(204);
        assertThat(refresh(f.refreshToken(), f.deviceId()).status()).isEqualTo(401);
    }

    @Test
    void protectedEndpointsNeedAValidToken() throws Exception {
        assertThat(call(get("/api/me")).status()).isEqualTo(401);
        assertThat(call(get("/api/me").header("Authorization", "Bearer not-a-jwt")).status()).isEqualTo(401);
        assertThat(call(get("/api/public/config")).status()).isEqualTo(200);
    }

    @Test
    void expiredAccessTokenIsRejected() throws Exception {
        Farmer f = registerFarmer();
        assertThat(farmerGet(f, "/api/me").status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(31));
        assertThat(farmerGet(f, "/api/me").status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ OTP (enabled in the test profile)

    @Test
    void otpLoginStillWorksWhenEnabled() throws Exception {
        Farmer f = registerFarmer();
        assertThat(requestOtp(f.phone()).status()).isEqualTo(200);
        Res verified = verifyOtp(f.phone(), TEST_OTP, randomDevice());
        assertThat(verified.status()).isEqualTo(200);
        assertThat(verified.body().get("user").get("id").asString()).isEqualTo(f.id().toString());
    }

    @Test
    void wrongOtpBurnsTheChallengeAfterMaxAttempts() throws Exception {
        String phone = randomPhone();
        String device = randomDevice();
        requestOtp(phone);
        for (int i = 0; i < 5; i++) {
            assertThat(verifyOtp(phone, "000000", device).code()).isEqualTo("OTP_INVALID");
        }
        assertThat(verifyOtp(phone, TEST_OTP, device).code()).isEqualTo("OTP_ATTEMPTS_EXCEEDED");
    }

    @Test
    void otpExpiresAndIsRateLimitedAndStoredHashed() throws Exception {
        String phone = randomPhone();
        assertThat(requestOtp(phone).status()).isEqualTo(200);
        assertThat(requestOtp(phone).status()).isEqualTo(429);
        String stored = jdbc.sql("SELECT code_hash FROM otp_challenges WHERE phone = :p").param("p", phone)
                .query(String.class).single();
        assertThat(stored).isNotEqualTo(TEST_OTP).hasSize(64);
        clock.advance(Duration.ofMinutes(6));
        assertThat(verifyOtp(phone, TEST_OTP, randomDevice()).code()).isEqualTo("OTP_EXPIRED");
    }
}
