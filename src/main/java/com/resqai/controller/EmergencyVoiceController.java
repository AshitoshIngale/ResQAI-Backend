package com.resqai.controller;

import com.resqai.model.EmergencyFollowUpJobDocument;
import com.resqai.model.EmergencySituationReportDocument;
import com.resqai.model.EmergencyVoiceSessionDocument;
import jakarta.validation.Valid;
import com.resqai.model.UserDocument;
import com.resqai.repository.BystanderReportRepository;
import com.resqai.repository.EmergencyConditionTimelineRepository;
import com.resqai.repository.EmergencySituationReportRepository;
import com.resqai.repository.EmergencyVoiceSessionRepository;
import com.resqai.repository.UserRepository;
import com.resqai.service.EmergencyFollowUpScheduler;
import com.resqai.service.EmergencySituationReportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for the browser-based AI voice conversation (spec PART 16):
 * session creation with a SHORT-LIVED token, conversation history, situation
 * reports, condition timeline, manual condition updates, arrival
 * confirmation and incident closure. All authenticated (JWT); arrival
 * confirmation is restricted to the victim's own account (authorized
 * responder roles are a future phase).
 */
@RestController
@RequestMapping("/api/emergency")
public class EmergencyVoiceController {

    private final BystanderReportRepository reports;
    private final EmergencyVoiceSessionRepository voiceSessions;
    private final EmergencySituationReportRepository sitreps;
    private final EmergencyConditionTimelineRepository timeline;
    private final EmergencySituationReportService sitrepService;
    private final EmergencyFollowUpScheduler followUpScheduler;
    private final UserRepository users;

    public EmergencyVoiceController(BystanderReportRepository reports,
                                    EmergencyVoiceSessionRepository voiceSessions,
                                    EmergencySituationReportRepository sitreps,
                                    EmergencyConditionTimelineRepository timeline,
                                    EmergencySituationReportService sitrepService,
                                    EmergencyFollowUpScheduler followUpScheduler,
                                    UserRepository users) {
        this.reports = reports;
        this.voiceSessions = voiceSessions;
        this.sitreps = sitreps;
        this.timeline = timeline;
        this.sitrepService = sitrepService;
        this.followUpScheduler = followUpScheduler;
        this.users = users;
    }

    public static class VoiceSessionRequest {
        public String language;   // en | hi | mr
        public boolean micConsent;
    }

    /**
     * Creates a SHORT-LIVED voice session: returns the WebSocket URL and the
     * one-time session token. Mic consent is explicit (spec PART 11).
     */
    @PostMapping("/{incidentId}/voice/session")
    public ResponseEntity<Map<String, Object>> createVoiceSession(
            @PathVariable String incidentId,
            Authentication authentication,
            @Valid @RequestBody VoiceSessionRequest request) {
        var report = reports.findByReportId(incidentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "incident not found"));
        if (!"VERIFIED".equals(report.getIdentityVerification())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "voice session requires a verified incident");
        }
        if (!request.micConsent) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "microphone consent is required before the AI conversation");
        }
        String language = switch (request.language == null ? "en" : request.language) {
            case "hi" -> "hi";
            case "mr" -> "mr";
            default -> "en";
        };

        var victim = users.findByEmergencyCode(report.getEmergencyCode())
            .filter(UserDocument::isEnabled).orElse(null);

        var session = new EmergencyVoiceSessionDocument();
        session.setSessionId(UUID.randomUUID().toString());
        session.setSessionToken(UUID.randomUUID().toString() + UUID.randomUUID().toString().substring(0, 8));
        session.setIncidentId(incidentId);
        session.setReportId(incidentId);
        session.setEmergencyCode(report.getEmergencyCode());
        session.setVictimEmergencyId(report.getVictimEmergencyId());
        session.setVictimName(victim == null ? null : victim.getFullName());
        session.setLanguage(language);
        session.setExpiresAt(Instant.now().plusSeconds(1800)); // 30 minutes
        voiceSessions.save(session);

        // Persisted scheduler jobs: 5-minute report + 20-minute follow-up.
        sitrepService.scheduleFollowUps(session);
        sitrepService.recordTimeline(incidentId, "SYSTEM",
            "AI voice session started (" + language + ")", false);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.getSessionId());
        out.put("token", session.getSessionToken());
        out.put("wsUrl", "/ws/emergency/voice/" + incidentId);
        out.put("language", language);
        out.put("patientName", session.getVictimName());
        out.put("emergencyId", session.getVictimEmergencyId());
        return ResponseEntity.ok(out);
    }

    /** Full conversation history for the incident. */
    @GetMapping("/{incidentId}/conversation")
    public ResponseEntity<?> conversation(@PathVariable String incidentId,
                                          Authentication authentication) {
        requireAuth(authentication);
        return ResponseEntity.ok(Map.of(
            "timeline", timeline.findByIncidentId(incidentId),
            "sessions", voiceSessions.findByIncidentId(incidentId)));
    }

    /** All situation reports (with honest delivery statuses). */
    @GetMapping("/{incidentId}/reports")
    public ResponseEntity<?> reports(@PathVariable String incidentId, Authentication authentication) {
        requireAuth(authentication);
        return ResponseEntity.ok(sitreps.findByIncidentIdOrderByGeneratedAtAsc(incidentId));
    }

    /** The condition timeline. */
    @GetMapping("/{incidentId}/timeline")
    public ResponseEntity<?> timeline(@PathVariable String incidentId, Authentication authentication) {
        requireAuth(authentication);
        return ResponseEntity.ok(timeline.findByIncidentId(incidentId));
    }

    public static class ConditionUpdateRequest {
        public String observation;
    }

    /** Manual condition update from the bystander UI (recorded honestly). */
    @PostMapping("/{incidentId}/condition/update")
    public ResponseEntity<Map<String, Object>> conditionUpdate(
            @PathVariable String incidentId,
            Authentication authentication,
            @RequestBody ConditionUpdateRequest request) {
        requireAuth(authentication);
        sitrepService.recordTimeline(incidentId, "BYSTANDER_REPORTED",
            request.observation == null ? "" : request.observation, false);
        return ResponseEntity.ok(Map.of("recorded", true));
    }

    /**
     * Manual AMBULANCE ARRIVAL confirmation. Restricted to the victim's own
     * account (authorized responder roles are a future phase); the timeline
     * records WHO confirmed, so a bystander can never fake arrival.
     */
    @PostMapping("/{incidentId}/arrival/confirm")
    public ResponseEntity<Map<String, Object>> arrivalConfirm(
            @PathVariable String incidentId,
            Authentication authentication) {
        var user = requireAuth(authentication);
        var report = reports.findByReportId(incidentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "incident not found"));
        var victim = users.findByEmergencyCode(report.getEmergencyCode()).orElse(null);
        boolean authorized = victim != null && user != null
            && user.getId().equals(victim.getId());
        if (!authorized) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "only the patient's own account can confirm ambulance arrival in this prototype");
        }
        sitrepService.recordTimeline(incidentId, "RESPONDER_CONFIRMED",
            "Ambulance arrival confirmed by the patient's account (manual confirmation)", false);
        return ResponseEntity.ok(Map.of("arrivalConfirmed", true, "confirmedBy", "PATIENT_ACCOUNT"));
    }

    /** Formal incident closure. */
    @PostMapping("/{incidentId}/close")
    public ResponseEntity<Map<String, Object>> close(
            @PathVariable String incidentId,
            Authentication authentication) {
        requireAuth(authentication);
        var report = reports.findByReportId(incidentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "incident not found"));
        sitrepService.recordTimeline(incidentId, "RESPONDER_CONFIRMED",
            "Emergency closed by the patient's account", false);
        voiceSessions.findByIncidentId(incidentId).ifPresent(session -> {
            if ("ACTIVE".equals(session.getState())) {
                session.setState("ENDED");
                session.setEndReason("EMERGENCY_CLOSED");
                session.setEndedAt(Instant.now());
                voiceSessions.save(session);
            }
        });
        return ResponseEntity.ok(Map.of("closed", true));
    }

    private UserDocument requireAuth(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
            || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByIdAndEnabledTrue(authentication.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Authentication required"));
    }
}
