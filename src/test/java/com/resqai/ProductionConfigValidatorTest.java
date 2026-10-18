package com.resqai;

import com.resqai.config.ProductionConfigValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production configuration guard tests (Phase 7 spec: prevent accidental
 * localhost fallback and weak JWT secrets on hosted deployments).
 */
class ProductionConfigValidatorTest {

  @Test
  void localDevelopmentWithoutRenderEnvIsNeverBlocked() {
    List<String> errors = ProductionConfigValidator.validate(
      false, "mongodb://localhost:27017", "local-dev-secret-anything");
    assertTrue(errors.isEmpty());
  }

  @Test
  void hostedDeploymentWithAtlasUriAndStrongSecretPasses() {
    List<String> errors = ProductionConfigValidator.validate(
      true,
      "mongodb+srv://user:pass@cluster0.xxxxx.mongodb.net/?retryWrites=true&w=majority",
      "Kk0WJ2p9bG9yZ LoyQXNzdWVydA==long-enough-secret-value");
    assertTrue(errors.isEmpty());
  }

  @Test
  void hostedDeploymentWithLocalhostUriIsRejected() {
    List<String> errors = ProductionConfigValidator.validate(
      true, "mongodb://localhost:27017", "a-strong-enough-production-secret-value");
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("localhost"));
    assertTrue(errors.get(0).contains("Atlas"));
  }

  @Test
  void missingMongoUriOnRenderIsRejected() {
    List<String> errors = ProductionConfigValidator.validate(true, null, "a-strong-enough-production-secret-value");
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("SPRING_DATA_MONGODB_URI"));
  }

  @Test
  void weakJwtSecretOnRenderIsRejected() {
    List<String> errors = ProductionConfigValidator.validate(
      true, "mongodb+srv://user:pass@cluster0.mongodb.net/", "local-dev-secret-replace");
    assertEquals(1, errors.size());
    assertTrue(errors.get(0).contains("JWT_SECRET"));
  }
}
