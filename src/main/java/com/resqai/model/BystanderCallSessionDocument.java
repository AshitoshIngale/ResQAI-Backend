package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * One bystander real-time AI first-aid call session (spec PART 7/14).
 * Driven by Twilio Gather speech turns; state persists per turn so the
 * conversation survives webhook round-trips.
 */
@Document(collection = "bystander_call_sessions")
public class BystanderCallSessionDocument {

    @Id
    private String id;

    @Indexed(name = "idx_session_call_sid", unique = true)
    private String callSid;

    @Indexed(name = "idx_session_report")
    private String reportId;

    private String incidentId;
    private String bystanderNumber;
    private String language;          // en | hi | mr
    private String sessionStatus = "ACTIVE"; // ACTIVE | COMPLETED | FAILED
    private int stepIndex = -1;       // -1 = intro not yet played
    private List<String> answers = new java.util.ArrayList<>();
    private String medicalContextJson;
    private Instant startedAt = Instant.now();
    private Instant endedAt;

    public String getId() { return id; }
    public String getCallSid() { return callSid; }
    public void setCallSid(String callSid) { this.callSid = callSid; }
    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }
    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public String getBystanderNumber() { return bystanderNumber; }
    public void setBystanderNumber(String bystanderNumber) { this.bystanderNumber = bystanderNumber; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getSessionStatus() { return sessionStatus; }
    public void setSessionStatus(String sessionStatus) { this.sessionStatus = sessionStatus; }
    public int getStepIndex() { return stepIndex; }
    public void setStepIndex(int stepIndex) { this.stepIndex = stepIndex; }
    public List<String> getAnswers() { return answers; }
    public void setAnswers(List<String> answers) { this.answers = answers; }
    public String getMedicalContextJson() { return medicalContextJson; }
    public void setMedicalContextJson(String medicalContextJson) { this.medicalContextJson = medicalContextJson; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
