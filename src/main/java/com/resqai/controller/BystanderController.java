package com.resqai.controller;

import com.resqai.dto.BystanderDtos;
import com.resqai.service.BystanderReportService;

import com.resqai.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public bystander endpoints — no victim impersonation and no authentication
 * required, protected by strict per-IP rate limits instead. Emergency-code
 * lookups return the minimum authorized summary only (no medical data:
 * the responder-disclosure policy is PENDING and documented).
 */
@RestController
@RequestMapping("/api/v1")
public class BystanderController {

    private final BystanderReportService reportService;
    private final RateLimiter rateLimiter;
    private final com.resqai.service.SarvamSpeechService sarvamSpeech;
    private final com.resqai.repository.BystanderReportRepository reportRepository;

    public BystanderController(BystanderReportService reportService, RateLimiter rateLimiter,
                               com.resqai.service.SarvamSpeechService sarvamSpeech,
                               com.resqai.repository.BystanderReportRepository reportRepository) {
        this.reportService = reportService;
        this.rateLimiter = rateLimiter;
        this.sarvamSpeech = sarvamSpeech;
        this.reportRepository = reportRepository;
    }

    /**
     * Transcribes a bystander voice note through Sarvam and stores ONLY the
     * transcript text (limited information) against the report. The audio
     * itself is never persisted. Honest statuses for every failure mode.
     */
    @PostMapping("/bystander/reports/{reportId}/voice-note")
    public ResponseEntity<java.util.Map<String, Object>> voiceNote(
            @org.springframework.web.bind.annotation.PathVariable String reportId,
            @org.springframework.web.bind.annotation.RequestBody VoiceNoteRequest request,
            jakarta.servlet.http.HttpServletRequest http) {
        if (!rateLimiter.tryConsume(com.resqai.security.RateLimiter.Policy.BYSTANDER_REPORT, clientKey(http))) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        byte[] audio;
        try {
            audio = java.util.Base64.getDecoder().decode(
                request == null || request.audioBase64 == null ? "" : request.audioBase64);
        } catch (IllegalArgumentException e) {
            out.put("status", "FAILED");
            out.put("detail", "audioBase64 is not valid Base64.");
            return ResponseEntity.badRequest().body(out);
        }
        var result = sarvamSpeech.transcribe(audio, request == null ? null : request.languageCode);
        out.put("status", result.status().name());
        out.put("detail", result.detail());
        if (result.transcript() != null) {
            out.put("transcript", result.transcript());
            var report = reportRepository.findByReportId(reportId);
            report.ifPresent(r -> {
                r.setVoiceTranscript(result.transcript());
                r.setServerUpdatedAt(java.time.Instant.now());
                reportRepository.save(r);
            });
            if (report.isEmpty()) {
                out.put("detail", "Transcribed, but the report is not on the server yet (offline queue still syncing).");
            }
        }
        return ResponseEntity.ok(out);
    }

    public static class VoiceNoteRequest {
        public String audioBase64;
        public String languageCode;
    }

    @GetMapping("/emergency-ids/{emergencyCode}/summary")
    public ResponseEntity<BystanderDtos.SummaryResponse> summary(
        @PathVariable String emergencyCode,
        HttpServletRequest http) {
        if (!rateLimiter.tryConsume(RateLimiter.Policy.EMERGENCY_LOOKUP, clientKey(http))) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Too many lookups");
        }
        return ResponseEntity.ok(reportService.resolve(emergencyCode));
    }

    @PostMapping("/bystander/reports")
    public ResponseEntity<BystanderDtos.ReportResponse> createReport(
        @Valid @RequestBody BystanderDtos.ReportRequest request,
        HttpServletRequest http) {
        if (!rateLimiter.tryConsume(RateLimiter.Policy.BYSTANDER_REPORT, clientKey(http))) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Too many reports");
        }
        BystanderDtos.ReportResponse response = reportService.create(request);
        // Duplicate replay -> 200; first submission -> 201.
        return response.duplicate
            ? ResponseEntity.ok(response)
            : ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private String clientKey(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        String ip = forwarded != null ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }
}
