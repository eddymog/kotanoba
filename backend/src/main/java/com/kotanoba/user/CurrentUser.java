package com.kotanoba.user;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Reads the acting user from Spring Security's context, populated per-request
 * by {@link JwtAuthenticationFilter}. Safe as a singleton bean despite being
 * "the current user": SecurityContextHolder is thread-local, so each request
 * thread sees its own authentication, not a shared one.
 *
 * <p>Previously hardcoded to the row seeded by V2__seed_dev_user.sql — see
 * V3__refresh_token.sql's comment on why that row was left in place rather
 * than deleted (Flyway migrations already applied to a live database aren't
 * rewritten after the fact). It's just inert legacy data now.
 */
@Component
public class CurrentUser {

    public long id() {
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
