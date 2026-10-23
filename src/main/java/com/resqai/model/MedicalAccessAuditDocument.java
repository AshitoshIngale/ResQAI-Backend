package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Audit trail for sensitive medical-data access through the bystander
 * emergency workflow. Records WHAT was accessed and WHEN — never the medical
 * values themselves, and never credentials.
 */
@Document(collection = "medical_access_audit")
public class MedicalAccessAuditDocument {

    @Id
    private String id;

    /** Which report triggered the disclosure. */
    @Indexed(name = "idx_audit_report")
    private String reportId;

    /** The victim's opaque emergency code (not their identity fields). */
    private String emergencyCode;

    /** BYSTANDER_REPORT_DISCLOSURE | EMERGENCY_LOOKUP. */
    private String action;

    private Instant accessedAt = Instant.now();

    public MedicalAccessAuditDocument() {}

    public MedicalAccessAuditDocument(String reportId, String emergencyCode, String action) {
        this.reportId = reportId;
        this.emergencyCode = emergencyCode;
        this.action = action;
    }

    public String getId() { return id; }
    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }
    public String getEmergencyCode() { return emergencyCode; }
    public void setEmergencyCode(String emergencyCode) { this.emergencyCode = emergencyCode; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Instant getAccessedAt() { return accessedAt; }
    public void setAccessedAt(Instant accessedAt) { this.accessedAt = accessedAt; }
}
