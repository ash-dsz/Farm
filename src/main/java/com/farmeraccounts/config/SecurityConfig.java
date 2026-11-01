package com.farmeraccounts.config;

import com.farmeraccounts.access.AccessGuardFilter;
import com.farmeraccounts.access.FarmerAccessService;
import com.farmeraccounts.admin.AdminSessionFilter;
import com.farmeraccounts.admin.AdminSessionService;
import com.farmeraccounts.auth.TokenService;
import com.farmeraccounts.common.ErrorResponse;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Three independent filter chains:
 * <ol>
 *   <li>/api/admin/** - admin dashboard, cookie session (separate from farmer auth, never accepts farmer JWTs)</li>
 *   <li>/api/** - farmer API, stateless JWT + kill-switch access guard</li>
 *   <li>everything else - static admin UI and health check only</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String ADMIN_CSP =
            "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                    + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    private final ObjectMapper json;

    public SecurityConfig(ObjectMapper json) {
        this.json = json;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public JwtEncoder jwtEncoder(AppProperties props) {
        SecretKey key = TokenService.signingKey(props);
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    public JwtDecoder jwtDecoder(AppProperties props, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(TokenService.signingKey(props))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(props.jwt().issuer()),
                new JwtClaimValidator<String>(TokenService.CLAIM_TYPE, TokenService.FARMER_TYPE::equals)));
        return decoder;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminChain(HttpSecurity http, AdminSessionService sessions) throws Exception {
        http.securityMatcher("/api/admin/**")
                // CSRF: SameSite=Strict cookie + mandatory custom header, checked in AdminSessionFilter.
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(ADMIN_CSP)))
                .addFilterBefore(new AdminSessionFilter(sessions, json), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login").permitAll()
                        .requestMatchers("/api/admin/admins/**").hasRole("OWNER")
                        .anyRequest().hasRole("ADMIN"))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized()).accessDeniedHandler(forbidden()));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain farmerApiChain(HttpSecurity http, JwtDecoder decoder, FarmerAccessService access)
            throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority("ROLE_FARMER")));
        http.securityMatcher("/api/**")
                .csrf(c -> c.disable()) // stateless bearer tokens, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/otp/request", "/api/auth/otp/verify",
                                "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .anyRequest().hasRole("FARMER"))
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()))
                .addFilterAfter(new AccessGuardFilter(access, json), BearerTokenAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized()).accessDeniedHandler(forbidden()));
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain webChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(ADMIN_CSP)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/admin", "/admin/**", "/error").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    private AuthenticationEntryPoint unauthorized() {
        return (req, res, ex) -> write(res, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Please log in");
    }

    private AccessDeniedHandler forbidden() {
        return (req, res, ex) -> write(res, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    private void write(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getOutputStream(), new ErrorResponse(code, message, Map.of()));
    }
}
