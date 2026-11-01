package com.farmeraccounts.auth;

import com.farmeraccounts.common.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class AuthController {

    public record Credentials(@NotBlank @Size(max = 20) String phone, @NotBlank @Size(max = 100) String password) {}

    public record OtpRequest(@NotBlank @Size(max = 20) String phone) {}

    public record OtpVerifyRequest(@NotBlank @Size(max = 20) String phone, @NotBlank @Size(max = 10) String code) {}

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {}

    public record PasswordChange(@Size(max = 100) String currentPassword, @NotBlank @Size(max = 100) String newPassword) {}

    public record ProfileRequest(@Size(max = 100) String name, @Size(max = 5) String preferredLanguage) {}

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<AuthService.AuthResponse> register(@Validated @RequestBody Credentials body, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(body.phone(), body.password(),
                ClientInfo.deviceId(req), ClientInfo.ip(req), req.getHeader(ClientInfo.VERSION_HEADER)));
    }

    @PostMapping("/api/auth/login")
    public AuthService.AuthResponse login(@Validated @RequestBody Credentials body, HttpServletRequest req) {
        return auth.login(body.phone(), body.password(), ClientInfo.deviceId(req), ClientInfo.ip(req),
                req.getHeader(ClientInfo.VERSION_HEADER));
    }

    @PostMapping("/api/auth/refresh")
    public AuthService.AuthResponse refresh(@Validated @RequestBody RefreshRequest body, HttpServletRequest req) {
        return auth.refresh(body.refreshToken(), ClientInfo.deviceId(req), req.getHeader(ClientInfo.VERSION_HEADER));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest body) {
        auth.logout(body == null ? null : body.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/auth/otp/request")
    public OtpService.OtpRequestResult requestOtp(@Validated @RequestBody OtpRequest body, HttpServletRequest req) {
        return auth.requestOtp(body.phone(), ClientInfo.ip(req), req.getHeader(ClientInfo.VERSION_HEADER));
    }

    @PostMapping("/api/auth/otp/verify")
    public AuthService.AuthResponse verifyOtp(@Validated @RequestBody OtpVerifyRequest body, HttpServletRequest req) {
        return auth.verifyOtp(body.phone(), body.code(), ClientInfo.deviceId(req), ClientInfo.ip(req),
                req.getHeader(ClientInfo.VERSION_HEADER));
    }

    @PutMapping("/api/me/password")
    public ResponseEntity<Void> changePassword(FarmerPrincipal farmer, @Validated @RequestBody PasswordChange body) {
        auth.changePassword(farmer, body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/me")
    public AuthService.UserView me(FarmerPrincipal farmer) {
        return auth.me(farmer);
    }

    @PutMapping("/api/me/profile")
    public AuthService.UserView profile(FarmerPrincipal farmer, @Validated @RequestBody ProfileRequest body) {
        return auth.updateProfile(farmer, body.name(), body.preferredLanguage());
    }
}
