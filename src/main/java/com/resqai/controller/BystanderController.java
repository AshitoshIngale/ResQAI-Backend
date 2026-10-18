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

    public BystanderController(BystanderReportService reportService, RateLimiter rateLimiter) {
        this.reportService = reportService;
        this.rateLimiter = rateLimiter;
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
