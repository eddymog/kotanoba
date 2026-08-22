package com.kotanoba;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// UserDetailsServiceAutoConfiguration excluded: it exists to auto-configure a
// default in-memory user (logging a random generated password) when nothing
// else has set up authentication. This app's auth is entirely
// JwtAuthenticationFilter + SecurityConfig — Spring's UserDetailsService /
// AuthenticationManager machinery is never used, so the default user is
// dead, confusing noise rather than a real fallback.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class KotanobaApplication {

    public static void main(String[] args) {
        SpringApplication.run(KotanobaApplication.class, args);
    }
}
