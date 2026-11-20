package com.smartdrone.api.security;

import java.io.IOException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.smartdrone.api.error.ProblemJson;
import com.smartdrone.application.apikeys.ApiKeyAuthenticator;
import com.smartdrone.application.common.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Api-Key";

    private final ApiKeyAuthenticator authenticator;

    public ApiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            ProblemJson.write(response, ErrorCode.API_KEY_UNAUTHORIZED);
            return;
        }
        try {
            var authenticated = authenticator.authenticate(header.trim());
            SecurityContextHolder.getContext().setAuthentication(
                    new ApiKeyAuthenticationToken(authenticated.keyId(), authenticated.scopes()));
        } catch (ApiKeyAuthenticator.ApiKeyAuthException ex) {
            ErrorCode code = ex.failure() == ApiKeyAuthenticator.Failure.RATE_LIMITED
                    ? ErrorCode.API_KEY_RATE_LIMITED : ErrorCode.API_KEY_UNAUTHORIZED;
            ProblemJson.write(response, code);
            return;
        }
        chain.doFilter(request, response);
    }
}