package com.resqai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * ResQAI backend. Authentication is JWT-filter based; Spring Boot's generated
 * in-memory user/password fallback is deliberately excluded so production
 * never carries an unintended credential source.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ResQaiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResQaiApplication.class, args);
    }
}
