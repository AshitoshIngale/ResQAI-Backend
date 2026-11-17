package com.resqai.repository;

import com.resqai.model.EmergencyFollowUpJobDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmergencyFollowUpJobRepository
        extends MongoRepository<EmergencyFollowUpJobDocument, String> {

    /** Due pending jobs ordered by due time (scheduler poll). */
    List<EmergencyFollowUpJobDocument> findByStateAndDueAtLessThanEqualOrderByDueAtAsc(
            String state, Instant now, Pageable pageable);

    /** Stuck RUNNING jobs (scheduler died mid-run; safe reclaim). */
    List<EmergencyFollowUpJobDocument> findByStateAndClaimedAtBefore(
            String state, Instant claimedBefore, Pageable pageable);

    List<EmergencyFollowUpJobDocument> findByIncidentId(String incidentId);

    Optional<EmergencyFollowUpJobDocument> findByIdempotencyKey(String idempotencyKey);
}
