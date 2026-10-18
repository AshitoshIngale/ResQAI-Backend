package com.resqai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** DTOs for the emergency-profile and emergency-contacts endpoints. */
public final class ProfileDtos {
    private ProfileDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EmergencyProfileResponse {
        public String dateOfBirth;
        public String bloodGroup;
        public List<String> allergies;
        public List<String> medicalConditions;
        public List<String> currentMedications;
        public String infoSource;
        public String abhaStatus;
        public Long updatedAt;
    }

    public static class EmergencyProfileRequest {
        @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$", message = "dateOfBirth must be YYYY-MM-DD")
        public String dateOfBirth;

        @Pattern(regexp = "^(A|B|AB|O)[+-]$|^UNKNOWN$|^$", message = "unsupported blood group")
        public String bloodGroup;

        @Size(max = 50, message = "at most 50 allergies")
        public List<@Size(max = 120) String> allergies;

        @Size(max = 50, message = "at most 50 conditions")
        public List<@Size(max = 120) String> medicalConditions;

        @Size(max = 50, message = "at most 50 medications")
        public List<@Size(max = 120) String> currentMedications;
    }

    public static class ContactResponse {
        public String contactId;
        public String name;
        public String phone;
        public String relationship;
        public boolean primary;
        public Long createdAt;
        public Long updatedAt;
    }

    public static class ContactRequest {
        @NotBlank @Size(max = 120)
        public String name;

        @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "phone must be E.164, e.g. +919876543210")
        public String phone;

        @Size(max = 60)
        public String relationship;

        public boolean primary;
    }

    public static class ContactListResponse {
        public List<ContactResponse> contacts;
    }

    /** Optional client-generated id so contact ids stay stable across devices. */
    public static class ContactUpsertRequest extends ContactRequest {
        @Size(max = 64)
        public String contactId;
    }
}
