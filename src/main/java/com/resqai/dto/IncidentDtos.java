package com.resqai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Request/response DTOs for incident persistence and synchronization. */
public final class IncidentDtos {
    private IncidentDtos() {}

    public static class IncidentRequest {
        /** Client-generated stable UUID (idempotency key). */
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            message = "incidentId must be a UUID")
        public String incidentId;

        @NotBlank @Size(max = 40)
        public String category;

        @NotNull
        public Long clientCreatedAt;

        @Size(max = 60)
        public String idempotencyKey;

        @Size(max = 200)
        public String source;

        @Size(max = 40)
        public String victimEmergencyId;

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
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IncidentResponse {
        public String id;
        public String incidentId;
        public String ownerUserId;
        public String status;
        public String category;
        public Long clientCreatedAt;
        public Double latitude;
        public Double longitude;
        public Double locationAccuracyMeters;
        public Long locationCapturedAt;
        public String source;
        public String victimEmergencyId;
        public String description;
        public Long serverCreatedAt;
        public boolean duplicate;
    }

    public static class BatchSyncRequest {
        @NotNull
        @Size(min = 1, max = 100, message = "batch must contain 1-100 incidents")
        public List<IncidentRequest> incidents;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BatchResultItem {
        public String id;
        public String incidentId;
        /** CREATED | DUPLICATE | REJECTED */
        public String result;
        public String error;
        public Long serverCreatedAt;

        public BatchResultItem(String incidentId, String result) {
            this.incidentId = incidentId;
            this.result = result;
        }
    }

    public static class BatchSyncResponse {
        public int created;
        public int duplicates;
        public int rejected;
        public List<BatchResultItem> results;
    }
}
