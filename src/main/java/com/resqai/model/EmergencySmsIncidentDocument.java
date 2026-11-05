package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * An owner SOS that arrived as a signed cellular SMS through Twilio Number A
 * (spec PART 4/5). The phone needs NO internet for this path - only SMS.
 */
@Document(collection = "emergency_sms_incidents")
@CompoundIndex(name = "uniq_incident_id", def = "{'incidentId': 1}", unique = true)
public class EmergencySmsIncidentDocument {

    @Id
    private String id;

    /** Client-generated unique incident id from the signed payload. */
    @Indexed(name = "idx_sms_incident_id")
    private String incidentId;

    private String patientId;
    private String deviceId;
    private String patientName;
    private String emergencyCategory;
    private String bloodGroup;
    private String allergies;
    private String medicalConditions;
    private Double latitude;
    private Double longitude;
    private Double locationAccuracyMeters;
    private String preferredLanguage;
    private String senderNumber;
    private String validationStatus;
    private Instant receivedAt = Instant.now();

    // Ambulance call tracking (Number A -> configured destination)
    private String callSid;
    private String callStatus;
    private String callDetail;
    private Instant callInitiatedAt;
    private Instant callAnsweredAt;
    private Instant callCompletedAt;
    private String failureReason;

    public String getId() { return id; }
    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public String getPatientId() { return patientId; }
    public void setPatientId(String patientId) { this.patientId = patientId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }
    public String getEmergencyCategory() { return emergencyCategory; }
    public void setEmergencyCategory(String emergencyCategory) { this.emergencyCategory = emergencyCategory; }
    public String getBloodGroup() { return bloodGroup; }
    public void setBloodGroup(String bloodGroup) { this.bloodGroup = bloodGroup; }
    public String getAllergies() { return allergies; }
    public void setAllergies(String allergies) { this.allergies = allergies; }
    public String getMedicalConditions() { return medicalConditions; }
    public void setMedicalConditions(String medicalConditions) { this.medicalConditions = medicalConditions; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getLocationAccuracyMeters() { return locationAccuracyMeters; }
    public void setLocationAccuracyMeters(Double locationAccuracyMeters) { this.locationAccuracyMeters = locationAccuracyMeters; }
    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String preferredLanguage) { this.preferredLanguage = preferredLanguage; }
    public String getSenderNumber() { return senderNumber; }
    public void setSenderNumber(String senderNumber) { this.senderNumber = senderNumber; }
    public String getValidationStatus() { return validationStatus; }
    public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public String getCallSid() { return callSid; }
    public void setCallSid(String callSid) { this.callSid = callSid; }
    public String getCallStatus() { return callStatus; }
    public void setCallStatus(String callStatus) { this.callStatus = callStatus; }
    public String getCallDetail() { return callDetail; }
    public void setCallDetail(String callDetail) { this.callDetail = callDetail; }
    public Instant getCallInitiatedAt() { return callInitiatedAt; }
    public void setCallInitiatedAt(Instant callInitiatedAt) { this.callInitiatedAt = callInitiatedAt; }
    public Instant getCallAnsweredAt() { return callAnsweredAt; }
    public void setCallAnsweredAt(Instant callAnsweredAt) { this.callAnsweredAt = callAnsweredAt; }
    public Instant getCallCompletedAt() { return callCompletedAt; }
    public void setCallCompletedAt(Instant callCompletedAt) { this.callCompletedAt = callCompletedAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
