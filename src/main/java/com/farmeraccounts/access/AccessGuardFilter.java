package com.farmeraccounts.access;

import com.farmeraccounts.common.ApiException;
import com.farmeraccounts.common.ClientInfo;
import com.farmeraccounts.common.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Server-side enforcement of the kill switch. Runs after JWT authentication on the farmer API and
 * rejects the request when the platform is suspended / under maintenance, the farmer is blocked or
 * the client version is no longer supported. This never relies on the mobile app's own checks.
 */
public class AccessGuardFilter extends OncePerRequestFilter {

    private final FarmerAccessService access;
    private final ObjectMapper json;

    public AccessGuardFilter(FarmerAccessService access, ObjectMapper json) {
        this.access = access;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && auth.isAuthenticated()) {
            try {
                UUID userId = UUID.fromString(jwt.getToken().getSubject());
                access.assertFarmerAllowed(userId, request.getHeader(ClientInfo.VERSION_HEADER));
                access.touchActivity(userId);
            } catch (ApiException e) {
                response.setStatus(e.status().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                json.writeValue(response.getOutputStream(), ErrorResponse.of(e));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
