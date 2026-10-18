package com.resqai.repository;

import com.resqai.model.EmergencyProfileDocument;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface EmergencyProfileRepository extends MongoRepository<EmergencyProfileDocument, String> {
    Optional<EmergencyProfileDocument> findByUserId(String userId);
}
