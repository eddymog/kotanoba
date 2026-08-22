package com.kotanoba.user;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code Authorization: Bearer <token>}, verifies the JWT's signature
 * and expiry, and — if valid — sets the user id as the request's principal.
 * No database lookup here: the token's signature is itself the proof the id
 * inside it is genuine, which is the entire point of a self-issued JWT. A
 * per-request user lookup would be strictly extra work for no extra safety.
 *
 * <p>Missing or invalid tokens simply leave the SecurityContext unauthenticated
 * and fall through — {@link SecurityConfig} is what actually rejects the
 * request with 401/403 for endpoints that require authentication.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            jwtService.verifyAndGetUserId(token).ifPresent(userId -> {
                var authentication = new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }
}
