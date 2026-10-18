package com.resqai.repository;

import com.resqai.model.AssessmentDocument;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface AssessmentRepository extends MongoRepository<AssessmentDocument, String> {
    Optional<AssessmentDocument> findByAssessmentIdAndOwnerUserId(String assessmentId, String ownerUserId);
}
