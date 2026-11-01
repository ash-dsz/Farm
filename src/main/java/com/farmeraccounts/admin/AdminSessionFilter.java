package com.farmeraccounts.admin;

import com.farmeraccounts.common.ClientInfo;
import com.farmeraccounts.common.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticates admin API calls from the session cookie and enforces a custom request header on
 * state-changing requests (CSRF defence in addition to the SameSite=Strict cookie).
 */
public class AdminSessionFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-Requested-With";
    public static final String CSRF_VALUE = "farmer-accounts-admin";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final AdminSessionService sessions;
    private final ObjectMapper json;

    public AdminSessionFilter(AdminSessionService sessions, ObjectMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE_METHODS.contains(request.getMethod()) && !CSRF_VALUE.equals(request.getHeader(CSRF_HEADER))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(),
                    new ErrorResponse("CSRF_CHECK_FAILED", "Missing request header", Map.of()));
            return;
        }
        String token = cookie(request);
        sessions.authenticate(token, ClientInfo.ip(request)).ifPresent(admin -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            if (admin.isOwner()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_OWNER"));
            }
            var auth = new UsernamePasswordAuthenticationToken(admin, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        });
        chain.doFilter(request, response);
    }

    static String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (AdminSessionService.COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
