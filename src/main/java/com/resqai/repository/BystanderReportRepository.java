package com.resqai.repository;

import com.resqai.model.BystanderReportDocument;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface BystanderReportRepository extends MongoRepository<BystanderReportDocument, String> {
    Optional<BystanderReportDocument> findByReportId(String reportId);
}
