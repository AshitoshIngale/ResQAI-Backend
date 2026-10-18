package com.resqai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fails the deployment at startup when a hosted (Render) environment is
 * detected with a configuration that cannot work (localhost MongoDB fallback,
 * weak JWT secret). The error messages name the exact environment variables
 * to set in the Render dashboard and never expose secret values.
 */
@Component
public class ProductionConfigGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigGuard.class);

    private final Environment environment;

    public ProductionConfigGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean renderHosted = ProductionConfigValidator.isRenderHosted(
            System.getenv("RENDER"));
        if (!renderHosted) {
            return; // local development: no hosted constraints
        }

        String mongoUri = environment.getProperty("spring.data.mongodb.uri");
        String jwtSecret = environment.getProperty("resqai.jwt.secret");

        List<String> errors = ProductionConfigValidator.validate(
            renderHosted, mongoUri, jwtSecret);
        if (errors.isEmpty()) {
            log.info("Production configuration validation passed");
            return;
        }
        for (String error : errors) {
            log.error("CONFIGURATION ERROR: {}", error);
        }
        throw new IllegalStateException(
            "Hosted configuration is invalid. Fix the environment variables in the " +
            "Render dashboard and redeploy. " + errors.size() + " error(s) logged above.");
    }
}
