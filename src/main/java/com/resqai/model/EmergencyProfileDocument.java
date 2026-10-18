package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Emergency-profile document, separate from credentials. Served only to the
 * authenticated owner via /users/me. infoSource records where the medical
 * data came from: USER_PROVIDED or ABHA_IMPORTED.
 */
@Document(collection = "emergency_profiles")
@CompoundIndex(name = "uniq_user", def = "{'userId': 1}", unique = true)
public class EmergencyProfileDocument {

    @Id
    private String id;

    /** Owner user id. */
    private String userId;

    private String dateOfBirth;
    private String bloodGroup;
    private List<String> allergies = new ArrayList<>();
    private List<String> medicalConditions = new ArrayList<>();
    private List<String> currentMedications = new ArrayList<>();

    private String infoSource = "USER_PROVIDED";

    private List<Contact> emergencyContacts = new ArrayList<>();

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public static class Contact implements ContactView {
        private String name;
        private String phone;
        private String relationship;

        public Contact() {}

        public Contact(String name, String phone, String relationship) {
            this.name = name;
            this.phone = phone;
            this.relationship = relationship;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getRelationship() { return relationship; }
        public void setRelationship(String relationship) { this.relationship = relationship; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(String dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getBloodGroup() { return bloodGroup; }
    public void setBloodGroup(String bloodGroup) { this.bloodGroup = bloodGroup; }
    public List<String> getAllergies() { return allergies; }
    public void setAllergies(List<String> allergies) { this.allergies = allergies; }
    public List<String> getMedicalConditions() { return medicalConditions; }
    public void setMedicalConditions(List<String> medicalConditions) { this.medicalConditions = medicalConditions; }
    public List<String> getCurrentMedications() { return currentMedications; }
    public void setCurrentMedications(List<String> currentMedications) { this.currentMedications = currentMedications; }
    public String getInfoSource() { return infoSource; }
    public void setInfoSource(String infoSource) { this.infoSource = infoSource; }
    public List<Contact> getEmergencyContacts() { return emergencyContacts; }
    public void setEmergencyContacts(List<Contact> emergencyContacts) { this.emergencyContacts = emergencyContacts; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
