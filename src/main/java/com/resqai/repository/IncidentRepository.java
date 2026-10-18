package com.resqai.repository;

import com.resqai.model.IncidentDocument;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface IncidentRepository extends MongoRepository<IncidentDocument, String> {
    Optional<IncidentDocument> findByIncidentIdAndOwnerUserId(String incidentId, String ownerUserId);
    Page<IncidentDocument> findByOwnerUserIdOrderByClientCreatedAtDesc(String ownerUserId, Pageable pageable);
    long countByOwnerUserId(String ownerUserId);
}
