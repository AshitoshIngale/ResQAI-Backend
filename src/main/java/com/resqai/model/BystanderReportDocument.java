package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A bystander's emergency report. Deliberately a SEPARATE collection from
 * emergency incidents: reports have a different lifecycle (submitted by
 * unauthenticated witnesses, then triaged), different ownership (anonymous),
 * and different idempotency (reportId). Multiple reports may describe the
 * same real accident — each report is preserved and linked to a common
 * incident only when the matching evidence is sufficient; uncertain cases
 * stay UNMATCHED for later review. Nothing here claims a dispatch occurred.
 */
@Document(collection = "bystander_reports")
@CompoundIndex(name = "uniq_report_id", def = "{'reportId': 1}", unique = true)
@CompoundIndex(name = "idx_code_created", def = "{'emergencyCode': 1, 'clientCreatedAt': -1}")
@CompoundIndex(name = "idx_linked_incident", def = "{'linkedIncidentId': 1}", sparse = true)
public class BystanderReportDocument {

    @Id
    private String id;

    /** Client-generated stable UUID — idempotency key for retries. */
    private String reportId;

    /** The opaque code scanned/typed by the bystander (RESQAI:1:<code> payload). */
    private String emergencyCode;

    /** Display Emergency ID (RQ-...) when the code resolved to a real account; else null. */
    private String victimEmergencyId;

    /** VERIFIED (resolved against the backend) | UNVERIFIED (offline/unresolved — honest state). */
    private String identityVerification;

    private String category;

    @Indexed(name = "idx_report_status")
    private String status = "RECEIVED";

    private String description;

    private Double latitude;
    private Double longitude;
    private Double locationAccuracyMeters;
    private Instant locationCapturedAt;

    private Instant clientCreatedAt;

    private String source;

    private Instant serverCreatedAt = Instant.now();
    private Instant serverUpdatedAt = Instant.now();

    // ---- association metadata (Phase 4 matching, no dispatch claims) ----

    /** Incident this report was linked to; null while unmatched. */
    private String linkedIncidentId;

    /** UNMATCHED | MATCHED (evidence sufficient) — uncertain matches stay UNMATCHED. */
    private String matchStatus = "UNMATCHED";

    /** How it was matched, e.g. "VICTIM_ID", "TIME_PROXIMITY_CATEGORY". */
    private String matchSignals;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }
    public String getEmergencyCode() { return emergencyCode; }
    public void setEmergencyCode(String emergencyCode) { this.emergencyCode = emergencyCode; }
    public String getVictimEmergencyId() { return victimEmergencyId; }
    public void setVictimEmergencyId(String victimEmergencyId) { this.victimEmergencyId = victimEmergencyId; }
    public String getIdentityVerification() { return identityVerification; }
    public void setIdentityVerification(String identityVerification) { this.identityVerification = identityVerification; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getLocationAccuracyMeters() { return locationAccuracyMeters; }
    public void setLocationAccuracyMeters(Double locationAccuracyMeters) { this.locationAccuracyMeters = locationAccuracyMeters; }
    public Instant getLocationCapturedAt() { return locationCapturedAt; }
    public void setLocationCapturedAt(Instant locationCapturedAt) { this.locationCapturedAt = locationCapturedAt; }
    public Instant getClientCreatedAt() { return clientCreatedAt; }
    public void setClientCreatedAt(Instant clientCreatedAt) { this.clientCreatedAt = clientCreatedAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Instant getServerCreatedAt() { return serverCreatedAt; }
    public void setServerCreatedAt(Instant serverCreatedAt) { this.serverCreatedAt = serverCreatedAt; }
    public Instant getServerUpdatedAt() { return serverUpdatedAt; }
    public void setServerUpdatedAt(Instant serverUpdatedAt) { this.serverUpdatedAt = serverUpdatedAt; }
    public String getLinkedIncidentId() { return linkedIncidentId; }
    public void setLinkedIncidentId(String linkedIncidentId) { this.linkedIncidentId = linkedIncidentId; }
    public String getMatchStatus() { return matchStatus; }
    public void setMatchStatus(String matchStatus) { this.matchStatus = matchStatus; }
    public String getMatchSignals() { return matchSignals; }
    public void setMatchSignals(String matchSignals) { this.matchSignals = matchSignals; }

    /** Honest Twilio notification outcome: NOT_CONFIGURED / SENT / FAILED / INVALID_CREDENTIALS. */
    private String notificationStatus;

    /** Compact per-recipient outcome summary (no credentials, no full record). */
    private String notificationDetail;

    /** Sarvam voice-note transcript (limited information only). */
    private String voiceTranscript;

    private Instant notificationAttemptedAt;

    public String getNotificationStatus() { return notificationStatus; }
    public void setNotificationStatus(String notificationStatus) { this.notificationStatus = notificationStatus; }
    public String getNotificationDetail() { return notificationDetail; }
    public void setNotificationDetail(String notificationDetail) { this.notificationDetail = notificationDetail; }
    public String getVoiceTranscript() { return voiceTranscript; }
    public void setVoiceTranscript(String voiceTranscript) { this.voiceTranscript = voiceTranscript; }
    public Instant getNotificationAttemptedAt() { return notificationAttemptedAt; }
    public void setNotificationAttemptedAt(Instant notificationAttemptedAt) { this.notificationAttemptedAt = notificationAttemptedAt; }
}
