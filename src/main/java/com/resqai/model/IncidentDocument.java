package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A genuine emergency incident uploaded by the Android client.
 *
 * Idempotency: (ownerUserId, incidentId) is UNIQUE — incidentId is the
 * client-generated UUID that the Room layer already uses as localIncidentId.
 * Retrying the same upload therefore never creates a second record; the
 * service returns the original document marked as a duplicate.
 *
 * Status reflects only what is actually known: OPEN until a later phase adds
 * an authorized dispatch/response provider. Nothing here claims an ambulance
 * was notified or dispatched.
 */
@Document(collection = "emergency_incidents")
@CompoundIndex(name = "uniq_owner_incident", def = "{'ownerUserId': 1, 'incidentId': 1}", unique = true)
@CompoundIndex(name = "idx_owner_created", def = "{'ownerUserId': 1, 'clientCreatedAt': -1}")
@CompoundIndex(name = "idx_status", def = "{'status': 1}")
public class IncidentDocument {

    @Id
    private String id;

    /** Client-generated stable UUID (Room localIncidentId). */
    private String incidentId;

    /** Owner derived from the JWT principal — never from the request body. */
    private String ownerUserId;

    /** OPEN | ACKNOWLEDGED | RESPONDING | RESOLVED | CLOSED */
    private String status = "OPEN";

    /** Client incident type, e.g. SOS_BUTTON, HARDWARE_TRIGGER_SOS. */
    private String category;

    private Double latitude;
    private Double longitude;
    private Double locationAccuracyMeters;
    private Instant locationCapturedAt;

    /** When the incident occurred on the device. */
    private Instant clientCreatedAt;

    /** Client idempotency key (trigger id) — informational; uniqueness comes from incidentId. */
    @Indexed(name = "idx_idempotency_key")
    private String idempotencyKey;

    /** e.g. SOS_BUTTON, SOS_BUTTON (Press & Hold) source description. */
    private String source;

    /** Victim's display Emergency ID when known to the reporting device. */
    private String victimEmergencyId;

    private String description;

    private Instant serverCreatedAt = Instant.now();
    private Instant serverUpdatedAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
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
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getVictimEmergencyId() { return victimEmergencyId; }
    public void setVictimEmergencyId(String victimEmergencyId) { this.victimEmergencyId = victimEmergencyId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getServerCreatedAt() { return serverCreatedAt; }
    public void setServerCreatedAt(Instant serverCreatedAt) { this.serverCreatedAt = serverCreatedAt; }
    public Instant getServerUpdatedAt() { return serverUpdatedAt; }
    public void setServerUpdatedAt(Instant serverUpdatedAt) { this.serverUpdatedAt = serverUpdatedAt; }
}
