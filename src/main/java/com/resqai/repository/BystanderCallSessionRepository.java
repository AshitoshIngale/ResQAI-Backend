package com.resqai.repository;

import com.resqai.model.BystanderCallSessionDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BystanderCallSessionRepository
        extends MongoRepository<BystanderCallSessionDocument, String> {
    BystanderCallSessionDocument findByCallSid(String callSid);
    BystanderCallSessionDocument findFirstByReportIdOrderByStartedAtDesc(String reportId);
}
