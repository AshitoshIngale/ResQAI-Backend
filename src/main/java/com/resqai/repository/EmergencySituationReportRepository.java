package com.resqai.repository;

import com.resqai.model.EmergencySituationReportDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface EmergencySituationReportRepository
        extends MongoRepository<EmergencySituationReportDocument, String> {

    Optional<EmergencySituationReportDocument> findByReportIdempotencyKey(String idempotencyKey);

    List<EmergencySituationReportDocument> findByIncidentIdOrderByGeneratedAtAsc(String incidentId);
}
