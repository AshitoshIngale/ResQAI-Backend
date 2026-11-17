package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The evolving condition timeline of one emergency incident.
 *
 * Every condition observation is attributed to an HONEST source:
 *  BYSTANDER_REPORT - what the bystander typed when creating the report.
 *  AI_CONVERSATION  - what the bystander told the AI assistant (reported,
 *                     never clinically verified by the system).
 *  BYSTANDER_UPDATE - a later explicit update via the app or voice session.
 *  RESPONDER        - a confirmed responder/dispatcher entry.
 *
 * The system never converts a reported observation into a verified clinical
 * fact; the source label travels with every entry.
 */
@Document(collection = "emergency_condition_timeline")
public class EmergencyConditionTimelineDocument {

    @Id
    private String id;

    @Indexed(name = "idx_timeline_incident", unique = true)
    private String incidentId;

    private final List<Entry> entries = new ArrayList<>();

    private Instant updatedAt = Instant.now();

    public EmergencyConditionTimelineDocument() {}

    public EmergencyConditionTimelineDocument(String incidentId) {
        this.incidentId = incidentId;
    }

    public static class Entry {
        /** ISO-8601 instant of the observation. */
        public Instant at = Instant.now();
        /** BYSTANDER_REPORT | AI_CONVERSATION | BYSTANDER_UPDATE | RESPONDER */
        public String source;
        /** Free-text observation (from the bystander, not clinically verified). */
        public String observation;
        /** Structured deltas, e.g. {"consciousness":"unresponsive"}. */
        public Map<String, String> fields = new LinkedHashMap<>();
        /** True for red-flag escalations (unconscious, not breathing, etc.). */
        public boolean deterioration;

        public Entry() {}
        public Entry(String source, String observation, Map<String, String> fields, boolean deterioration) {
            this.source = source;
            this.observation = observation;
            this.fields = fields == null ? new LinkedHashMap<>() : fields;
            this.deterioration = deterioration;
        }
    }

    public String getId() { return id; }
    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public List<Entry> getEntries() { return entries; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
