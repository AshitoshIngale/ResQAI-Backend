package com.resqai.repository;

import com.resqai.model.EmergencySmsIncidentDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EmergencySmsIncidentRepository
        extends MongoRepository<EmergencySmsIncidentDocument, String> {
    boolean existsByIncidentId(String incidentId);
    EmergencySmsIncidentDocument findByIncidentId(String incidentId);
}
