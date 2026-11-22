package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * One browser-based emergency voice session (Workflow B replacement).
 *
 * This REPLACES the former Twilio "CALL 2" (a phone call placed to the
 * bystander for AI first aid). The AI conversation now happens in the
 * bystander's browser (embedded WebView) over a secure WebSocket; Twilio is
 * used only for the existing ambulance telephone integration.
 *
 * States are HONEST:
 *  ACTIVE       - a browser session is connected and streaming.
 *  DISCONNECTED - the browser dropped (network loss); the incident and
 *                 conversation persist and the bystander may reconnect.
 *  ENDED        - the bystander ended the session or the emergency closed.
 *
 * A session is never created twice for the same incident while ACTIVE
 * (incidentId unique index + state check = idempotent reconnects).
 */
@Document(collection = "voice_sessions")
public class EmergencyVoiceSessionDocument {

    @Id
    private String id;

    /** Server-generated session id (UUID), exposed to the browser. */
    @Indexed(name = "idx_voice_session_sessionId", unique = true)
    private String sessionId;

    /** Short-lived bearer token for the WebSocket handshake (30 min TTL). */
    @Indexed(name = "uniq_voice_session_token", unique = true)
    private String sessionToken;

    /** Incident this conversation belongs to. */
    @Indexed(name = "idx_voice_session_incident")
    private String incidentId;

    /** Bystander report that created the incident context (may be null). */
    private String reportId;

    /** Victim emergency code (for medical-context authorization). */
    private String emergencyCode;

    /** Display emergency id (RQ-XXXXXXXX), safe to show the bystander. */
    private String victimEmergencyId;

    /** Victim display name, authorized for this session's UI. */
    private String victimName;

    /** Selected conversation language: en | hi | mr. */
    private String language = "en";

    /** ACTIVE | DISCONNECTED | ENDED */
    private String state = "ACTIVE";

    /** Why the session ended: BYSTANDER | EMERGENCY_CLOSED | SERVER | null. */
    private String endReason;

    private Instant startedAt = Instant.now();
    private Instant lastActivityAt = Instant.now();
    private Instant endedAt;
    /** Short-lived token expiry (30 minutes from session creation). */
    private Instant expiresAt;

    /** Count of (re)connections for this session (browser refresh support). */
    private int connectionCount;

    /** Epoch ms when the bystander granted microphone consent. */
    private Instant consentAt;

    /** Current mute state reported by the browser. */
    private boolean micMuted;

    /** Conversation engine used: LLM (Gemini) or DETERMINISTIC fallback. */
    private String engineMode = "LLM";

    /** Facts collected from the bystander, e.g. conscious=no, bleeding=yes. */
    private Map<String, String> collectedFacts = new java.util.LinkedHashMap<>();
    /** Question keys already asked - the engine never repeats a question. */
    private java.util.List<String> askedKeys = new java.util.ArrayList<>();
    /** Verbatim observations reported by the bystander (for the sitrep). */
    private java.util.List<String> observations = new java.util.ArrayList<>();
    /** True when a serious deterioration was reported (immediate sitrep). */
    private boolean urgentFlagged = false;
    /** Follow-up message queued by the scheduler for delivery in-session. */
    private String pendingAgentMessage;

    /** Incident GPS snapshot (from the creating report), for sitreps. */
    private Double latitude;
    private Double longitude;

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public Map<String, String> getCollectedFacts() { return collectedFacts; }
    public void setCollectedFacts(Map<String, String> collectedFacts) { this.collectedFacts = collectedFacts; }
    public java.util.List<String> getAskedKeys() { return askedKeys; }
    public void setAskedKeys(java.util.List<String> askedKeys) { this.askedKeys = askedKeys; }
    public java.util.List<String> getObservations() { return observations; }
    public void setObservations(java.util.List<String> observations) { this.observations = observations; }
    public boolean isUrgentFlagged() { return urgentFlagged; }
    public void setUrgentFlagged(boolean urgentFlagged) { this.urgentFlagged = urgentFlagged; }
    public String getPendingAgentMessage() { return pendingAgentMessage; }
    public void setPendingAgentMessage(String pendingAgentMessage) { this.pendingAgentMessage = pendingAgentMessage; }

    public EmergencyVoiceSessionDocument() {}

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }
    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }
    public String getEmergencyCode() { return emergencyCode; }
    public void setEmergencyCode(String emergencyCode) { this.emergencyCode = emergencyCode; }
    public String getVictimEmergencyId() { return victimEmergencyId; }
    public void setVictimEmergencyId(String victimEmergencyId) { this.victimEmergencyId = victimEmergencyId; }
    public String getVictimName() { return victimName; }
    public void setVictimName(String victimName) { this.victimName = victimName; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getEndReason() { return endReason; }
    public void setEndReason(String endReason) { this.endReason = endReason; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant lastActivityAt) { this.lastActivityAt = lastActivityAt; }
    public boolean isEnded() { return "ENDED".equals(state); }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public int getConnectionCount() { return connectionCount; }
    public void setConnectionCount(int connectionCount) { this.connectionCount = connectionCount; }
    public Instant getConsentAt() { return consentAt; }
    public void setConsentAt(Instant consentAt) { this.consentAt = consentAt; }
    public boolean isMicMuted() { return micMuted; }
    public void setMicMuted(boolean micMuted) { this.micMuted = micMuted; }
    public String getEngineMode() { return engineMode; }
    public void setEngineMode(String engineMode) { this.engineMode = engineMode; }
}
