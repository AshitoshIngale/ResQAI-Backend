package com.resqai.service;

import com.resqai.exception.VerificationException;
import com.resqai.model.PendingVerification;
import com.resqai.repository.PendingVerificationRepository;

import com.resqai.security.Argon2idPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Issues and consumes expiring, attempt-bounded verification codes.
 * Secrets exist only as Argon2id hashes at rest.
 */
@Component
public class VerificationService {

    private final PendingVerificationRepository repository;
    private final Argon2idPasswordEncoder encoder;

    public VerificationService(PendingVerificationRepository repository, Argon2idPasswordEncoder encoder) {
        this.repository = repository;
        this.encoder = encoder;
    }

    /** Issues a fresh code for (kind, key), replacing any previous one. */
    public void issue(PendingVerification.Kind kind, String key, String code, long ttlMinutes, int maxAttempts) {
        repository.deleteByKindAndKey(kind, key);
        repository.save(new PendingVerification(
            UUID.randomUUID().toString(),
            kind,
            key,
            encoder.encode(code),
            java.time.Instant.now().plusSeconds(ttlMinutes * 60),
            maxAttempts
        ));
    }

    /**
     * Verifies and consumes the code.
     *
     * @throws VerificationException.Expired          code existed but timed out
     * @throws VerificationException.Invalid          unknown key or wrong code
     * @throws VerificationException.TooManyAttempts  attempt budget exhausted
     */
    public void consume(PendingVerification.Kind kind, String key, String code) {
        Optional<PendingVerification> found = repository.findFirstByKindAndKeyOrderByExpiresAtDesc(kind, key);
        PendingVerification pending = found.orElse(null);
        if (pending == null) {
            throw new VerificationException.Invalid();
        }
        if (pending.isExpired()) {
            repository.delete(pending);
            throw new VerificationException.Expired();
        }
        if (pending.getAttempts() >= pending.getMaxAttempts()) {
            repository.delete(pending);
            throw new VerificationException.TooManyAttempts(900);
        }

        pending.setAttempts(pending.getAttempts() + 1);
        boolean matches = encoder.matches(code, pending.getSecretHash());
        if (!matches) {
            repository.save(pending);
            throw new VerificationException.Invalid();
        }
        repository.delete(pending);
    }
}
