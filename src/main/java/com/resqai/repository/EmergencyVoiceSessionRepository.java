package com.resqai.repository;

import com.resqai.model.EmergencyVoiceSessionDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface EmergencyVoiceSessionRepository
        extends MongoRepository<EmergencyVoiceSessionDocument, String> {

    Optional<EmergencyVoiceSessionDocument> findBySessionId(String sessionId);

    /** Short-lived bearer token lookup for the WebSocket handshake. */
    Optional<EmergencyVoiceSessionDocument> findBySessionToken(String sessionToken);

    /** All sessions for an incident (a genuine later emergency creates a new one). */
    java.util.List<EmergencyVoiceSessionDocument> findByIncidentIdOrderByStartedAtDesc(
            String incidentId);

    Optional<EmergencyVoiceSessionDocument> findByIncidentIdAndState(String incidentId, String state);
}
