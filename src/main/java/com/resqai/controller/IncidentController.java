package com.resqai.controller;

import com.resqai.dto.IncidentDtos;
import com.resqai.service.IncidentService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Incident endpoints. Ownership always comes from the JWT principal;
 * a client-supplied user id is never trusted.
 */
@RestController
@RequestMapping("/api/v1")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @PostMapping("/incidents")
    public ResponseEntity<IncidentDtos.IncidentResponse> create(
        Authentication authentication,
        @Valid @RequestBody IncidentDtos.IncidentRequest request) {
        String owner = requireOwner(authentication);
        IncidentDtos.IncidentResponse response = incidentService.create(owner, request);
        // First upload -> 201 Created; idempotent replay of the same client
        // incidentId -> 200 OK with duplicate=true, never a second record.
        return response.duplicate
            ? ResponseEntity.ok(response)
            : ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/incidents/{incidentId}")
    public ResponseEntity<IncidentDtos.IncidentResponse> get(
        Authentication authentication,
        @PathVariable String incidentId) {
        String owner = requireOwner(authentication);
        return ResponseEntity.ok(incidentService.get(owner, incidentId));
    }

    @GetMapping("/incidents/mine")
    public ResponseEntity<Page<IncidentDtos.IncidentResponse>> mine(
        Authentication authentication,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        String owner = requireOwner(authentication);
        return ResponseEntity.ok(incidentService.mine(owner, page, size));
    }

    @PostMapping("/sync/incidents")
    public ResponseEntity<IncidentDtos.BatchSyncResponse> syncBatch(
        Authentication authentication,
        @Valid @RequestBody IncidentDtos.BatchSyncRequest request) {
        String owner = requireOwner(authentication);
        return ResponseEntity.ok(incidentService.syncBatch(owner, request.incidents));
    }

    private String requireOwner(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authentication.getName();
    }
}
