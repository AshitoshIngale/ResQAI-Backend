package com.resqai.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup configuration guard for hosted (Render) deployments.
 *
 * Render always sets the RENDER environment variable. When it is present the
 * backend is treated as hosted and the configuration is validated BEFORE the
 * service accepts traffic:
 *
 *  - SPRING_DATA_MONGODB_URI must be present and must not point at localhost
 *    (a container-local MongoDB does not exist on Render).
 *  - JWT_SECRET must be a strong production value, not a local dev default.
 *
 * Violations are fatal: the application exits with a clear, secret-free error
 * so Render marks the deploy failed instead of serving a falsely running API.
 * Local development (no RENDER variable) is completely unaffected.
 */
public final class ProductionConfigValidator {

    private ProductionConfigValidator() {}

    public static boolean isRenderHosted(String renderEnvValue) {
        return renderEnvValue != null && !renderEnvValue.isBlank();
    }

    /** Returns a list of human-readable configuration errors; empty = valid. */
    public static List<String> validate(boolean renderHosted, String mongoUri, String jwtSecret) {
        List<String> errors = new ArrayList<>();
        if (!renderHosted) {
            return errors;
        }
        if (mongoUri == null || mongoUri.isBlank()) {
            errors.add(
                "SPRING_DATA_MONGODB_URI is required: set it in the Render dashboard " +
                "(Environment) to your MongoDB Atlas connection string.");
        } else if (mongoUri.contains("localhost") || mongoUri.contains("127.0.0.1")) {
            errors.add(
                "SPRING_DATA_MONGODB_URI points to localhost, which does not exist inside a " +
                "Render container. Set it to your MongoDB Atlas connection string " +
                "(mongodb+srv://...) in the Render dashboard.");
        }
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.length() < 32
            || jwtSecret.startsWith("local-dev-secret") || jwtSecret.startsWith("CHANGE_ME")) {
            errors.add(
                "JWT_SECRET must be a strong production value of at least 32 characters " +
                "(set JWT_SECRET in the Render dashboard; generate with: openssl rand -base64 48).");
        }
        return errors;
    }
}
