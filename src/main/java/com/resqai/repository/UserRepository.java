package com.resqai.repository;

import com.resqai.model.UserDocument;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<UserDocument, String> {
    Optional<UserDocument> findByNormalizedEmail(String normalizedEmail);
    Optional<UserDocument> findByNormalizedPhone(String normalizedPhone);
    boolean existsByNormalizedEmail(String normalizedEmail);
    boolean existsByNormalizedPhone(String normalizedPhone);
    Optional<UserDocument> findByIdAndEnabledTrue(String id);
    Optional<UserDocument> findByEmergencyCode(String emergencyCode);
}
