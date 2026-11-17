package com.resqai.repository;

import com.resqai.model.EmergencyConditionTimelineDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface EmergencyConditionTimelineRepository
        extends MongoRepository<EmergencyConditionTimelineDocument, String> {

    Optional<EmergencyConditionTimelineDocument> findByIncidentId(String incidentId);
}
