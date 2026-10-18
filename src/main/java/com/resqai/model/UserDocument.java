package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Authentication account document. Credentials are separated from the
 * emergency-profile document; the profile lives in EmergencyProfileDocument
 * and is only served to the authenticated owner.
 *
 * Invariants enforced by unique indexes (declared once, via @CompoundIndex):
 *  - normalizedEmail is unique
 *  - normalizedPhone is unique (when present)
 */
@Document(collection = "users")
@CompoundIndex(name = "uniq_email", def = "{'normalizedEmail': 1}", unique = true)
@CompoundIndex(name = "uniq_phone", def = "{'normalizedPhone': 1}", unique = true, sparse = true)
public class UserDocument {

    @Id
    private String id;

    private String fullName;

    /** Lower-cased, trimmed email used for uniqueness. */
    private String normalizedEmail;

    /** Display email as entered (trimmed). */
    private String email;

    /** E.164 phone used for uniqueness. */
    private String normalizedPhone;

    private String phone;

    /** Argon2id hash — never a raw or reversible password. */
    private String passwordHash;

    private boolean emailVerified;
    private Instant emailVerifiedAt;
    private boolean phoneVerified;
    private Instant phoneVerifiedAt;

    private boolean enabled = true;

    /** Epoch ms when the user accepted the privacy notice and terms. */
    private Long acceptedTermsAt;

    /** Server-issued human-readable emergency id (e.g. RQ-XXXXXXXX). Display only. */
    private String emergencyId;

    /**
     * Opaque 128-bit lookup code carried inside the victim's QR payload
     * (RESQAI:1:<code>). Unique and unenumerable — possession of the display
     * Emergency ID alone does not authorize any data access.
     */
    private String emergencyCode;

    private String preferredLanguage = "en";

    /** NOT_LINKED | LINK_PENDING | LINKED — no ABHA credentials are ever stored. */
    private String abhaStatus = "NOT_LINKED";

    /** Active refresh-token id (jti) — rotating: a new login invalidates the old session. */
    private String activeRefreshJti;
    private Instant activeRefreshExpiresAt;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    /** Free-text audit events WITHOUT credentials or medical content. */
    private List<AuditEvent> auditEvents = new ArrayList<>();

    public static class AuditEvent {
        private String action;      // REGISTER, VERIFY_EMAIL, VERIFY_PHONE, LOGIN, LOGOUT, PASSWORD_RESET...
        private Instant at;
        private String detail;      // e.g. "dev-mail-adapter", "otp-exceeded-attempts"

        public AuditEvent() {}

        public AuditEvent(String action, String detail) {
            this.action = action;
            this.detail = detail;
            this.at = Instant.now();
        }

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public Instant getAt() { return at; }
        public void setAt(Instant at) { this.at = at; }
        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
    }

    // --- getters/setters ---
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getNormalizedEmail() { return normalizedEmail; }
    public void setNormalizedEmail(String normalizedEmail) { this.normalizedEmail = normalizedEmail; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getNormalizedPhone() { return normalizedPhone; }
    public void setNormalizedPhone(String normalizedPhone) { this.normalizedPhone = normalizedPhone; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public void setEmailVerifiedAt(Instant emailVerifiedAt) { this.emailVerifiedAt = emailVerifiedAt; }
    public boolean isPhoneVerified() { return phoneVerified; }
    public void setPhoneVerified(boolean phoneVerified) { this.phoneVerified = phoneVerified; }
    public Instant getPhoneVerifiedAt() { return phoneVerifiedAt; }
    public void setPhoneVerifiedAt(Instant phoneVerifiedAt) { this.phoneVerifiedAt = phoneVerifiedAt; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Long getAcceptedTermsAt() { return acceptedTermsAt; }
    public void setAcceptedTermsAt(Long acceptedTermsAt) { this.acceptedTermsAt = acceptedTermsAt; }
    public String getEmergencyId() { return emergencyId; }
    public void setEmergencyId(String emergencyId) { this.emergencyId = emergencyId; }
    public String getEmergencyCode() { return emergencyCode; }
    public void setEmergencyCode(String emergencyCode) { this.emergencyCode = emergencyCode; }
    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String preferredLanguage) { this.preferredLanguage = preferredLanguage; }
    public String getAbhaStatus() { return abhaStatus; }
    public void setAbhaStatus(String abhaStatus) { this.abhaStatus = abhaStatus; }
    public String getActiveRefreshJti() { return activeRefreshJti; }
    public void setActiveRefreshJti(String activeRefreshJti) { this.activeRefreshJti = activeRefreshJti; }
    public Instant getActiveRefreshExpiresAt() { return activeRefreshExpiresAt; }
    public void setActiveRefreshExpiresAt(Instant activeRefreshExpiresAt) { this.activeRefreshExpiresAt = activeRefreshExpiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEvent> getAuditEvents() { return auditEvents; }
    public void setAuditEvents(List<AuditEvent> auditEvents) { this.auditEvents = auditEvents; }
}
