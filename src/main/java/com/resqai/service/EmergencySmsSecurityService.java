package com.resqai.service;

import com.resqai.model.UserDocument;
import com.resqai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Security for the owner's offline SMS emergency trigger (spec 4.4).
 *
 * The Android device signs the compact payload with an EC P-256 key held in
 * Android Keystore; the public key is registered server-side at device
 * registration (authenticated endpoint). The inbound SMS webhook validates:
 *   1. The sender number belongs to a registered user's device.
 *   2. The ECDSA signature over the canonical payload string.
 *   3. The timestamp is within +/- 10 minutes (carrier SMS can lag).
 *   4. The nonce was never seen before (replay protection).
 *   5. The incidentId was never used (duplicate protection).
 * An unauthenticated SMS containing the word SOS is NEVER a valid emergency.
 */
@Service
public class EmergencySmsSecurityService {

    private static final Logger log = LoggerFactory.getLogger(EmergencySmsSecurityService.class);
    private static final long MAX_CLOCK_SKEW_SECONDS = 600;

    private final UserRepository users;
    private final com.resqai.repository.EmergencySmsNonceRepository nonceRepository;

    /** In-process recent nonces for fast rejection; Mongo holds the durable set. */
    private final Set<String> recentNonces = ConcurrentHashMap.newKeySet();

    public EmergencySmsSecurityService(UserRepository users,
                                       com.resqai.repository.EmergencySmsNonceRepository nonceRepository) {
        this.users = users;
        this.nonceRepository = nonceRepository;
    }

    public record ValidationResult(
        boolean valid, String reason, UserDocument user) {}

    /**
     * Validates sender + signature + timestamp + nonce.
     * @param senderNumber the SMS sender (from the Twilio webhook)
     * @param payloadFields canonical fields in protocol order
     * @param signatureBase64 ECDSA (SHA256withECDSA) signature over the
     *        canonical string joined with '|'
     */
    public ValidationResult validate(String senderNumber, String[] payloadFields,
                                     String signatureBase64) {
        // Field layout: v|incidentId|patientId|deviceId|timestamp|nonce|category
        if (payloadFields.length < 7) {
            return invalid("payload has too few fields");
        }
        String version = payloadFields[0];
        if (!"1".equals(version)) return invalid("unknown protocol version " + version);
        String incidentId = payloadFields[1];
        String patientId = payloadFields[2];
        String deviceId = payloadFields[3];
        long timestamp;
        String nonce;
        try {
            timestamp = Long.parseLong(payloadFields[4]);
            nonce = payloadFields[5];
        } catch (NumberFormatException e) {
            return invalid("malformed timestamp");
        }

        UserDocument user = users.findById(patientId).filter(UserDocument::isEnabled)
            .orElse(null);
        if (user == null) return invalid("unknown patientId");
        if (user.getDevicePublicKey() == null || user.getDevicePublicKey().isBlank()) {
            return invalid("device has no registered signing key");
        }
        if (!senderNumber.equals(user.getPhone()) &&
            !senderNumber.replace(" ", "").equals(user.getPhone())) {
            // Carrier may format the sender differently; normalizing lightly.
            String digitsSender = senderNumber.replaceAll("[^0-9+]", "");
            String digitsUser = user.getPhone().replaceAll("[^0-9+]", "");
            if (!digitsSender.endsWith(digitsUser) && !digitsUser.endsWith(digitsSender)) {
                return invalid("sender does not match the registered phone");
            }
        }
        if (user.getDeviceId() != null && !user.getDeviceId().isBlank()
            && !user.getDeviceId().equals(deviceId)) {
            return invalid("deviceId does not match registration");
        }

        // Timestamp window.
        Instant now = Instant.now();
        Instant then = Instant.ofEpochSecond(timestamp);
        if (then.isBefore(now.minusSeconds(MAX_CLOCK_SKEW_SECONDS))
            || then.isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS))) {
            return invalid("timestamp outside the accepted window (replay?)");
        }

        // Nonce: in-process fast path, then durable check.
        if (!recentNonces.add(nonce)) {
            return invalid("nonce replayed");
        }
        if (nonceRepository.existsByNonce(nonce)) {
            return invalid("nonce replayed (durable)");
        }
        try {
            nonceRepository.save(new com.resqai.model.EmergencySmsNonceDocument(nonce, Instant.now()));
        } catch (Exception e) {
            log.warn("nonce persistence failed (continuing): {}", e.getMessage());
        }

        // ECDSA signature over the canonical string.
        String canonical = String.join("|", payloadFields);
        try {
            byte[] spki = Base64.getDecoder().decode(user.getDevicePublicKey());
            PublicKey publicKey = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(spki));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(signatureBase64))) {
                return invalid("signature verification FAILED");
            }
        } catch (Exception e) {
            return invalid("signature validation error: " + e.getMessage());
        }
        return new ValidationResult(true, "valid", user);
    }

    /** Duplicate-incident guard is separate so the webhook can decide. */
    public boolean isIncidentKnown(String incidentId,
                                   com.resqai.repository.EmergencySmsIncidentRepository repo) {
        return repo.existsByIncidentId(incidentId);
    }

    private static ValidationResult invalid(String reason) {
        log.warn("SMS emergency rejected: {}", reason);
        return new ValidationResult(false, reason, null);
    }
}
