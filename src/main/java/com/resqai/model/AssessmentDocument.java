package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Metadata of a client-side deterministic rules-engine assessment.
 * The rules themselves run on the device; only the structured result is
 * synchronized. Linked to an existing incident when one was selected.
 */
@Document(collection = "emergency_assessments")
@CompoundIndex(name = "uniq_owner_assessment", def = "{'ownerUserId': 1, 'assessmentId': 1}", unique = true)
@CompoundIndex(name = "idx_assessment_incident", def = "{'incidentId': 1}", sparse = true)
public class AssessmentDocument {

    @Id
    private String id;

    /** Client-generated stable UUID. */
    private String assessmentId;

    /** Owner from the JWT principal. */
    private String ownerUserId;

    /** Existing incident this assessment belongs to (may be null). */
    private String incidentId;

    /** MONITOR | URGENT | EMERGENCY — rule-based urgency, not a diagnosis. */
    private String urgency;

    private String triggeredRuleId;

    private String reportedSymptoms;

    private String missingInformation;

    private String language;

    private Instant clientCreatedAt;

    private Instant serverCreatedAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAssessmentId() { return assessmentId; }
    public void setAssessmentId(String assessmentId) { this.assessmentId = assessmentId; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public String getUrgency() { return urgency; }
    public void setUrgency(String urgency) { this.urgency = urgency; }
    public String getTriggeredRuleId() { return triggeredRuleId; }
    public void setTriggeredRuleId(String triggeredRuleId) { this.triggeredRuleId = triggeredRuleId; }
    public String getReportedSymptoms() { return reportedSymptoms; }
    public void setReportedSymptoms(String reportedSymptoms) { this.reportedSymptoms = reportedSymptoms; }
    public String getMissingInformation() { return missingInformation; }
    public void setMissingInformation(String missingInformation) { this.missingInformation = missingInformation; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Instant getClientCreatedAt() { return clientCreatedAt; }
    public void setClientCreatedAt(Instant clientCreatedAt) { this.clientCreatedAt = clientCreatedAt; }
    public Instant getServerCreatedAt() { return serverCreatedAt; }
    public void setServerCreatedAt(Instant serverCreatedAt) { this.serverCreatedAt = serverCreatedAt; }
}
