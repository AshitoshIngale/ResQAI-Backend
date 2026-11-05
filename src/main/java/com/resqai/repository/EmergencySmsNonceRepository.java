package com.resqai.repository;

import com.resqai.model.EmergencySmsNonceDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EmergencySmsNonceRepository
        extends MongoRepository<EmergencySmsNonceDocument, String> {
    boolean existsByNonce(String nonce);
}
