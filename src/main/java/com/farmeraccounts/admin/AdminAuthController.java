package com.farmeraccounts.admin;

import com.farmeraccounts.common.ClientInfo;
import com.farmeraccounts.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    public record LoginRequest(@NotBlank @Size(max = 60) String username, @NotBlank @Size(max = 128) String password,
                               @Size(max = 10) String totpCode) {}

    public record PasswordChange(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    public record CodeRequest(@NotBlank @Size(max = 10) String code) {}

    public record DisableTotpRequest(@NotBlank String password, @NotBlank @Size(max = 10) String code) {}

    public record CreateAdminRequest(@NotBlank String username, @NotBlank String password, @NotBlank String role) {}

    public record ActiveRequest(boolean active, @Size(max = 500) String reason) {}

    private final AdminSessionService sessions;
    private final AdminUserService users;
    private final AppProperties.Admin config;

    public AdminAuthController(AdminSessionService sessions, AdminUserService users, AppProperties props) {
        this.sessions = sessions;
        this.users = users;
        this.config = props.admin();
    }

    @PostMapping("/auth/login")
    public ResponseEntity<AdminSessionService.AdminView> login(@Validated @RequestBody LoginRequest body,
                                                               HttpServletRequest req) {
        var result = sessions.login(body.username(), body.password(), body.totpCode(), ClientInfo.ip(req),
                req.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(result.token(), result.maxAge()).toString())
                .body(result.admin());
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req) {
        sessions.logout(AdminSessionFilter.cookie(req));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/auth/me")
    public AdminSessionService.AdminView me(@AuthenticationPrincipal AdminPrincipal admin) {
        return sessions.view(admin.id());
    }

    @PostMapping("/auth/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AdminPrincipal admin,
                                               @Validated @RequestBody PasswordChange body) {
        users.changePassword(admin, body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/totp/setup")
    public AdminUserService.TotpSetup setupTotp(@AuthenticationPrincipal AdminPrincipal admin) {
        return users.setupTotp(admin);
    }

    @PostMapping("/auth/totp/enable")
    public ResponseEntity<Void> enableTotp(@AuthenticationPrincipal AdminPrincipal admin,
                                           @Validated @RequestBody CodeRequest body) {
        users.enableTotp(admin, body.code());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/totp/disable")
    public ResponseEntity<Void> disableTotp(@AuthenticationPrincipal AdminPrincipal admin,
                                            @Validated @RequestBody DisableTotpRequest body) {
        users.disableTotp(admin, body.password(), body.code());
        return ResponseEntity.noContent().build();
    }

    // ---- Administrator accounts (OWNER only, enforced in SecurityConfig) ----

    @GetMapping("/admins")
    public List<AdminUserService.AdminListItem> listAdmins() {
        return users.list();
    }

    @PostMapping("/admins")
    public AdminUserService.AdminListItem createAdmin(@AuthenticationPrincipal AdminPrincipal admin,
                                                      @Validated @RequestBody CreateAdminRequest body) {
        return users.create(admin, body.username(), body.password(), body.role());
    }

    @PostMapping("/admins/{id}/active")
    public ResponseEntity<Void> setActive(@AuthenticationPrincipal AdminPrincipal admin, @PathVariable UUID id,
                                          @Validated @RequestBody ActiveRequest body) {
        users.setActive(admin, id, body.active(), body.reason());
        return ResponseEntity.noContent().build();
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(AdminSessionService.COOKIE, value)
                .httpOnly(true)
                .secure(config.cookieSecure())
                .sameSite("Strict")
                .path("/api/admin")
                .maxAge(maxAge)
                .build();
    }
}
