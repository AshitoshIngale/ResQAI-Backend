package com.resqai.repository;

import com.resqai.model.FirstAidGuidanceLogDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FirstAidGuidanceLogRepository
        extends MongoRepository<FirstAidGuidanceLogDocument, String> {
}
