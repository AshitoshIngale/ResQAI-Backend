package com.resqai.controller;

import com.resqai.model.AssessmentDocument;
import com.resqai.repository.AssessmentRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

/**
 * Phase 5: metadata sync for device-side deterministic emergency assessments.
 * Idempotent on (ownerUserId, assessmentId). The rules themselves never run
 * here — this only stores the structured result the client produced offline.
 */
@RestController
@RequestMapping("/api/v1")
public class AssessmentController {

    public static class AssessmentRequest {
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            message = "assessmentId must be a UUID")
        public String assessmentId;

        /** Existing incident id, when the assessment belongs to one. */
        @Size(max = 64)
        public String incidentId;

        @NotBlank @Size(max = 20)
        public String urgency;

        @NotBlank @Size(max = 60)
        public String triggeredRuleId;

        @NotBlank @Size(max = 2000)
        public String reportedSymptoms;

        @Size(max = 500)
        public String missingInformation;

        @NotBlank @Size(max = 8)
        public String language;

        @NotNull
        public Long clientCreatedAt;
    }

    public static class AssessmentResponse {
        public String assessmentId;
        public String status;
        public boolean duplicate;
        public Long serverCreatedAt;
    }

    private final AssessmentRepository assessments;

    public AssessmentController(AssessmentRepository assessments) {
        this.assessments = assessments;
    }

    @PostMapping("/assessments")
    public ResponseEntity<AssessmentResponse> create(
        Authentication authentication,
        @Valid @RequestBody AssessmentRequest request) {
        String owner = requireOwner(authentication);

        Optional<AssessmentDocument> existing =
            assessments.findByAssessmentIdAndOwnerUserId(request.assessmentId, owner);
        if (existing.isPresent()) {
            AssessmentResponse response = new AssessmentResponse();
            response.assessmentId = request.assessmentId;
            response.status = existing.get().getUrgency();
            response.duplicate = true;
            response.serverCreatedAt = existing.get().getServerCreatedAt().toEpochMilli();
            return ResponseEntity.ok(response);
        }

        AssessmentDocument document = new AssessmentDocument();
        document.setAssessmentId(request.assessmentId);
        document.setOwnerUserId(owner);
        document.setIncidentId(request.incidentId);
        document.setUrgency(request.urgency);
        document.setTriggeredRuleId(request.triggeredRuleId);
        document.setReportedSymptoms(request.reportedSymptoms);
        document.setMissingInformation(request.missingInformation);
        document.setLanguage(request.language);
        document.setClientCreatedAt(Instant.ofEpochMilli(request.clientCreatedAt));
        assessments.save(document);

        AssessmentResponse response = new AssessmentResponse();
        response.assessmentId = request.assessmentId;
        response.status = request.urgency;
        response.duplicate = false;
        response.serverCreatedAt = document.getServerCreatedAt().toEpochMilli();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private String requireOwner(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
