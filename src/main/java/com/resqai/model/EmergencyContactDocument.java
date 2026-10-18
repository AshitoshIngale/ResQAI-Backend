package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * An emergency contact owned by one account. Lives in its own collection so
 * contacts have stable identifiers (matching the Android Room primary key)
 * and can be updated/deleted individually.
 */
@Document(collection = "emergency_contacts")
@CompoundIndex(name = "uniq_contact_id", def = "{'contactId': 1}", unique = true)
@CompoundIndex(name = "idx_owner", def = "{'ownerUserId': 1}")
public class EmergencyContactDocument implements ContactView {

    @Id
    private String id;

    /** Stable identifier shared with the client (Room contactId). */
    private String contactId;

    /** Owner account id — every read/write is scoped to this. */
    private String ownerUserId;

    private String name;

    /** E.164. */
    private String phone;

    private String relationship;

    private boolean primary;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public EmergencyContactDocument() {}

    public EmergencyContactDocument(String contactId, String ownerUserId, String name,
                                    String phone, String relationship, boolean primary) {
        this.contactId = contactId;
        this.ownerUserId = ownerUserId;
        this.name = name;
        this.phone = phone;
        this.relationship = relationship;
        this.primary = primary;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getContactId() { return contactId; }
    public void setContactId(String contactId) { this.contactId = contactId; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getRelationship() { return relationship; }
    public void setRelationship(String relationship) { this.relationship = relationship; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
