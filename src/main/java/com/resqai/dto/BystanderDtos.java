package com.resqai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** DTOs for bystander reporting and emergency-code resolution. */
public final class BystanderDtos {
    private BystanderDtos() {}

    /**
     * Emergency-code payload grammar accepted for lookup:
     *  - the opaque code itself (22 chars, base64url) as carried by RESQAI:1:<code>
     *  - a full versioned payload "RESQAI:1:<code>"
     * The human-readable RQ- display id is NOT accepted for lookup (enumeration).
     */
    public static final String EMERGENCY_CODE_PATTERN = "^[A-Za-z0-9_-]{16,64}$";

    public static class SummaryResponse {
        public String emergencyId;
        public boolean accountVerified;
        /** Honest capability marker: full responder medical-disclosure policy is PENDING. */
        public String medicalInfoPolicy;
    }

    public static class ReportRequest {
        /** Client-generated stable UUID — idempotency key. */
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            message = "reportId must be a UUID")
        public String reportId;

        /** Raw scanned/typed payload or bare code; server normalizes. */
        @NotBlank @Size(max = 200)
        public String emergencyCode;

        /** true only when the client resolved the code against the backend. */
        public boolean identityVerified = false;

        @NotBlank @Size(max = 40)
        public String category;

        @Size(max = 500)
        public String description;

        @jakarta.validation.constraints.Min(value = -90, message = "latitude out of range")
        @jakarta.validation.constraints.Max(value = 90, message = "latitude out of range")
        public Double latitude;

        @jakarta.validation.constraints.Min(value = -180, message = "longitude out of range")
        @jakarta.validation.constraints.Max(value = 180, message = "longitude out of range")
        public Double longitude;

        @jakarta.validation.constraints.Min(value = 0, message = "accuracy cannot be negative")
        public Double locationAccuracyMeters;

        public Long locationCapturedAt;

        @NotNull
        public Long clientCreatedAt;

        @Size(max = 200)
        public String source;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReportResponse {
        public String reportId;
        public String victimEmergencyId;
        public String identityVerification;
        public String status;
        public String category;
        public Long clientCreatedAt;
        public String linkedIncidentId;
        public String matchStatus;
        public String matchSignals;
        public Long serverCreatedAt;
        public boolean duplicate;
        public String notificationStatus;
        public String voiceTranscript;
        /** Authorized victim medical summary — minimum necessary fields, VERIFIED reports only. */
        public VictimSummary victimSummary;
        /** Automated emergency-call attempt outcome (honest; INITIATED is not a dispatch claim). */
        public String callStatus;
        public String callDetail;
    }

    /**
     * The MINIMUM authorized medical disclosure for an emergency responder.
     * Every field is null when unavailable — nothing is ever invented. The
     * server's record is authoritative; client-supplied values are ignored.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VictimSummary {
        public String emergencyName;
        public String bloodGroup;
        public List<String> allergies;
        public List<String> medicalConditions;
        public List<String> currentMedications;
        public String emergencyContactPhone;
    }
}
