package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One structured situation report generated for an emergency incident.
 *
 * Two report kinds exist:
 *  FIVE_MINUTE  - generated ~5 minutes into the AI conversation (never
 *                 delays the initial ambulance call; if serious
 *                 deterioration is detected first, an URGENT report is
 *                 produced immediately instead of waiting).
 *  URGENT       - produced as soon as a red-flag condition is detected.
 *  FOLLOW_UP    - produced by the ~20-minute follow-up when warranted.
 *
 * Delivery status is HONEST (spec section 8):
 *  GENERATED          - report text built.
 *  QUEUED             - accepted for delivery by the Twilio channel.
 *  SENT               - Twilio accepted the message (queued by Twilio).
 *  DELIVERY_CONFIRMED - Twilio status callback confirmed delivery.
 *  FAILED             - Twilio rejected or errored; retried by the scheduler.
 *
 * The report is delivered through the EXISTING supported Twilio ambulance
 * communication channel: an SMS from the ambulance-facing Twilio number
 * (Number A) to the configured ambulance/test destination.
 */
@Document(collection = "emergency_situation_reports")
@CompoundIndex(name = "idx_report_incident_kind", def = "{'incidentId': 1, 'kind': 1}")
public class EmergencySituationReportDocument {

    @Id
    private String id;

    /** Server-generated report id (UUID). */
    @Indexed(name = "idx_situation_report_reportId", unique = true)
    private String reportId;

    @Indexed(name = "idx_situation_report_incident")
    private String incidentId;

    private String reportIdempotencyKey;

    /** FIVE_MINUTE | URGENT | FOLLOW_UP */
    private String kind;

    /** GENERATED | QUEUED | SENT | DELIVERY_CONFIRMED | FAILED */
    private String status = "GENERATED";

    /** Human-readable structured paragraph sent to the ambulance. */
    private String reportText;

    /** Structured facts (JSON) kept for the timeline and audits. */
    private String structuredFactsJson;

    /** Victim display id the report concerns. */
    private String victimEmergencyId;

    /** Twilio message sid when the message was accepted. */
    private String deliveryMessageSid;

    /** Last Twilio status string (e.g. queued/sent/delivered/failed). */
    private String deliveryDetail;

    private Instant generatedAt = Instant.now();
    private Instant deliveredAt;

    public EmergencySituationReportDocument() {}

    public String getId() { return id; }
    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }
    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public String getReportIdempotencyKey() { return reportIdempotencyKey; }
    public void setReportIdempotencyKey(String reportIdempotencyKey) { this.reportIdempotencyKey = reportIdempotencyKey; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReportText() { return reportText; }
    public void setReportText(String reportText) { this.reportText = reportText; }
    public String getStructuredFactsJson() { return structuredFactsJson; }
    public void setStructuredFactsJson(String structuredFactsJson) { this.structuredFactsJson = structuredFactsJson; }
    public String getVictimEmergencyId() { return victimEmergencyId; }
    public void setVictimEmergencyId(String victimEmergencyId) { this.victimEmergencyId = victimEmergencyId; }
    public String getDeliveryMessageSid() { return deliveryMessageSid; }
    public void setDeliveryMessageSid(String deliveryMessageSid) { this.deliveryMessageSid = deliveryMessageSid; }
    public String getDeliveryDetail() { return deliveryDetail; }
    public void setDeliveryDetail(String deliveryDetail) { this.deliveryDetail = deliveryDetail; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
}
