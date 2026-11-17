package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A persistent follow-up job so scheduled emergency work SURVIVES backend
 * restarts and Render instance swaps (spec sections 8 and 9). Jobs are
 * claimed atomically by the scheduler; duplicates are prevented by the
 * unique (incidentId, type) index plus the state machine:
 *
 *   PENDING  -> RUNNING -> DONE
 *                    -> SKIPPED (precondition no longer applies, e.g.
 *                                ambulance arrived before the 5-min report)
 *                    -> FAILED (attempts exhausted; surfaced honestly)
 *
 * Types:
 *  FIVE_MINUTE_REPORT  - due ~5 min after the AI conversation starts.
 *  TWENTY_MIN_FOLLOWUP - due ~20 min after the incident was created.
 */
@Document(collection = "emergency_followup_jobs")
@CompoundIndex(name = "idx_job_incident_type", def = "{'incidentId': 1, 'type': 1}", unique = true)
public class EmergencyFollowUpJobDocument {

    public static final String TYPE_FIVE_MINUTE = "FIVE_MINUTE_REPORT";
    public static final String TYPE_TWENTY_MINUTE = "TWENTY_MIN_FOLLOWUP";

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_DONE = "DONE";
    public static final String STATE_SKIPPED = "SKIPPED";
    public static final String STATE_FAILED = "FAILED";

    @Id
    private String id;

    @Indexed(name = "idx_job_state_due")
    private String state = STATE_PENDING;

    @Indexed(name = "idx_job_due")
    private Instant dueAt;

    /** Epoch millis the scheduler last polled this job (starvation guard). */
    private Instant claimedAt;

    private String incidentId;
    private String type;
    /** Session the job was created from (may be gone at run time). */
    private String sessionId;
    /** Unique key preventing double execution across restarts. */
    private String idempotencyKey;

    private int attempts;
    private String lastError;
    private Instant executedAt;

    public EmergencyFollowUpJobDocument() {}

    public EmergencyFollowUpJobDocument(String incidentId, String type,
                                        String sessionId, Instant dueAt) {
        this.incidentId = incidentId;
        this.type = type;
        this.sessionId = sessionId;
        this.dueAt = dueAt;
        this.idempotencyKey = incidentId + ":" + type;
    }

    public String getId() { return id; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }
}
