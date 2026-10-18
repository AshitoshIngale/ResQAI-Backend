package com.resqai.repository;

import com.resqai.model.PendingVerification;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PendingVerificationRepository extends MongoRepository<PendingVerification, String> {
    Optional<PendingVerification> findFirstByKindAndKeyOrderByExpiresAtDesc(PendingVerification.Kind kind, String key);

    long deleteByKindAndKey(PendingVerification.Kind kind, String key);
}
