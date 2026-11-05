package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One line of the bystander AI conversation (spec PART 14). */
@Document(collection = "voice_conversation_history")
public class VoiceConversationHistoryDocument {

    @Id
    private String id;

    @Indexed(name = "idx_conv_session")
    private String sessionId;

    private String incidentId;
    /** BYSTANDER | AGENT | SYSTEM */
    private String conversationRole;
    private String message;
    private Instant timestamp = Instant.now();

    public VoiceConversationHistoryDocument() {}
    public VoiceConversationHistoryDocument(String sessionId, String incidentId,
                                            String role, String message) {
        this.sessionId = sessionId;
        this.incidentId = incidentId;
        this.conversationRole = role;
        this.message = message;
    }

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getIncidentId() { return incidentId; }
    public String getConversationRole() { return conversationRole; }
    public void setConversationRole(String conversationRole) { this.conversationRole = conversationRole; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getTimestamp() { return timestamp; }
}
