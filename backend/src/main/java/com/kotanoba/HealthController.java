package com.kotanoba;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deploy-platform health check (Render's healthCheckPath). Deliberately not
 * under /api — everything there requires auth (SecurityConfig), and a health
 * check has no credentials to offer.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
