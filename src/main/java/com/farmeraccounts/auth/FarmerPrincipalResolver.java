package com.farmeraccounts.auth;

import com.farmeraccounts.common.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class FarmerPrincipalResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(FarmerPrincipal.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw ApiException.unauthorized("UNAUTHORIZED", "Please log in");
        }
        Jwt jwt = token.getToken();
        Object otpAt = jwt.getClaim(TokenService.CLAIM_OTP_AT);
        Instant otpVerifiedAt = otpAt instanceof Instant i ? i
                : otpAt instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null;
        return new FarmerPrincipal(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString(TokenService.CLAIM_DEVICE),
                otpVerifiedAt);
    }
}
