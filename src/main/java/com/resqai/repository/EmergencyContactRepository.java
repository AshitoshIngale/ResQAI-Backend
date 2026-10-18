package com.resqai.repository;

import com.resqai.model.EmergencyContactDocument;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface EmergencyContactRepository extends MongoRepository<EmergencyContactDocument, String> {
    List<EmergencyContactDocument> findByOwnerUserIdOrderByCreatedAtAsc(String ownerUserId);
    Optional<EmergencyContactDocument> findByContactIdAndOwnerUserId(String contactId, String ownerUserId);
    long deleteByContactIdAndOwnerUserId(String contactId, String ownerUserId);
}
