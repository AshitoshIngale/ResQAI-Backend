package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Audit of every first-aid instruction given by the AI (spec PART 14). */
@Document(collection = "first_aid_guidance_logs")
public class FirstAidGuidanceLogDocument {

    @Id
    private String id;

    @Indexed(name = "idx_guid_session")
    private String sessionId;

    private String incidentId;
    private String guidanceCategory;
    private String guidanceStep;
    /** ENGINE_VALIDATED - every instruction comes from the reviewed knowledge base. */
    private String safetyValidationStatus = "ENGINE_VALIDATED";
    private Instant timestamp = Instant.now();

    public FirstAidGuidanceLogDocument() {}
    public FirstAidGuidanceLogDocument(String sessionId, String incidentId,
                                       String category, String step) {
        this.sessionId = sessionId;
        this.incidentId = incidentId;
        this.guidanceCategory = category;
        this.guidanceStep = step;
    }

    public String getSessionId() { return sessionId; }
    public String getIncidentId() { return incidentId; }
    public String getGuidanceCategory() { return guidanceCategory; }
    public String getGuidanceStep() { return guidanceStep; }
    public String getSafetyValidationStatus() { return safetyValidationStatus; }
    public Instant getTimestamp() { return timestamp; }
}
