package com.kotanoba.user;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    /**
     * claude.md specifies Argon2 explicitly. {@code createDelegatingPasswordEncoder()}
     * (Spring's usual default recommendation) would pick bcrypt, so this
     * builds Argon2 directly rather than taking the generic default.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * Only matters once frontend and backend are on different origins — local
     * dev never hits this (Vite's proxy makes the browser see same-origin
     * requests, per vite.config.ts). No credentials/cookies allowed: this app
     * doesn't use cookies, the JWT goes in an Authorization header, which
     * CORS doesn't gate the way it gates cookie-based auth.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        // CORS origin matching is an exact string comparison against the
        // browser's Origin header, which never has surrounding whitespace —
        // a pasted env var value that does (a trailing space is easy to
        // introduce and impossible to see in a dashboard text field) fails
        // to match and silently breaks every cross-origin request. Trimming
        // here means that class of mistake can't happen again.
        configuration.setAllowedOrigins(properties.allowedOrigins().stream().map(String::strip).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Stateless JWT API: no sessions, no cookies, no server-side login state
     * beyond the refresh_token table. CSRF protection exists to defend
     * cookie-based sessions from being ridden by a third-party site; there's
     * no cookie here for it to protect, so it's off rather than cargo-culted
     * on. httpBasic/formLogin are Spring Security's own auth mechanisms —
     * both disabled since JwtAuthenticationFilter is this app's only one.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            // Without httpBasic/formLogin, Spring Security has no default
            // AuthenticationEntryPoint to challenge with, and a missing/bad
            // token falls through as 403 (Forbidden) instead of 401
            // (Unauthorized) — wrong signal for "you didn't even try to
            // authenticate." This makes that case 401 explicitly.
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                // Any ResponseStatusException (e.g. AuthController's 409 on a
                // duplicate email, TextController's 404 on a missing/foreign
                // text) is delivered via HttpServletResponse.sendError(),
                // which makes the servlet container internally forward the
                // request to /error to render it. That forwarded request
                // runs back through this ENTIRE filter chain — Spring
                // Security does not know it's "the same request, just
                // rendering its own error" — and /error isn't under
                // /api/auth/**, so anyRequest().authenticated() denied it,
                // and the 401 from that denial overwrote the real status the
                // controller had already set. Verified with
                // logging.level.org.springframework.security=TRACE: the log
                // showed "Completed 409 CONFLICT" immediately followed by a
                // second full pass through the filter chain ending in "access
                // is denied." Permitting /error is the standard fix.
                .requestMatchers("/error").permitAll()
                // Render's healthCheckPath — has no credentials to offer.
                .requestMatchers("/health").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
