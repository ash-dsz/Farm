package com.farmeraccounts.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects plain-HTTP requests when {@code app.security.require-https} is on. Behind a reverse
 * proxy, {@code request.isSecure()} reflects X-Forwarded-Proto. The health check stays reachable
 * for internal load-balancer probes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpsEnforcementFilter extends OncePerRequestFilter {

    private final boolean requireHttps;

    public HttpsEnforcementFilter(AppProperties props) {
        this.requireHttps = props.security().requireHttps();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (requireHttps && !request.isSecure() && !"/actuator/health".equals(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"HTTPS_REQUIRED\",\"message\":\"HTTPS is required\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
