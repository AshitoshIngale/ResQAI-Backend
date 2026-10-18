package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One pending verification: email code, phone OTP or password-reset token.
 * The secret is stored ARGON2id-HASHED, never in clear text; each entry
 * carries an absolute expiry and a bounded attempt counter.
 */
@Document(collection = "pending_verifications")
public class PendingVerification {

    public enum Kind { EMAIL_VERIFICATION, PHONE_OTP, PASSWORD_RESET }

    @Id
    private final String id;
    private final Kind kind;
    /** kind-specific key: normalized email or E.164 phone. */
    private final String key;
    /** Argon2id hash of the code — never the code itself. */
    private final String secretHash;
    private final Instant expiresAt;
    private final int maxAttempts;
    private int attempts;

    public PendingVerification(String id, Kind kind, String key, String secretHash,
                               Instant expiresAt, int maxAttempts) {
        this.id = id;
        this.kind = kind;
        this.key = key;
        this.secretHash = secretHash;
        this.expiresAt = expiresAt;
        this.maxAttempts = maxAttempts;
        this.attempts = 0;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public String getId() { return id; }
    public Kind getKind() { return kind; }
    public String getKey() { return key; }
    public String getSecretHash() { return secretHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getMaxAttempts() { return maxAttempts; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
}
