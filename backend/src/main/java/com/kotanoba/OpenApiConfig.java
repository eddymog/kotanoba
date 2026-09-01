package com.kotanoba;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Springdoc otherwise renders this app's API under a generic default title —
 * this just names it and adds the bearer-JWT scheme so "Authorize" in
 * /swagger-ui/index.html actually works against endpoints under /api/**
 * (login/register are exempt; see SecurityConfig).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI kotanobaOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Kotanoba API")
                .description("Japanese reading trainer — import text, read with per-word status tracking, "
                    + "browse vocabulary. Register/login under /api/auth, then Authorize below with the "
                    + "returned accessToken to call everything else.")
                .version("v1"))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
            .components(new Components()
                .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")));
    }
}
