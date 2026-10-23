package com.resqai.repository;

import com.resqai.model.MedicalAccessAuditDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MedicalAccessAuditRepository
        extends MongoRepository<MedicalAccessAuditDocument, String> {
}
