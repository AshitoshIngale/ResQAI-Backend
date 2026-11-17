package com.resqai.repository;

import com.resqai.model.EmergencyVoiceSessionDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface EmergencyVoiceSessionRepository
        extends MongoRepository<EmergencyVoiceSessionDocument, String> {

    Optional<EmergencyVoiceSessionDocument> findBySessionId(String sessionId);

    /** Short-lived bearer token lookup for the WebSocket handshake. */
    Optional<EmergencyVoiceSessionDocument> findBySessionToken(String sessionToken);

    Optional<EmergencyVoiceSessionDocument> findByIncidentId(String incidentId);
}
