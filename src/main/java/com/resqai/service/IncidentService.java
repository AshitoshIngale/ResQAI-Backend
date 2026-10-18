package com.resqai.service;

import com.resqai.dto.IncidentDtos;
import com.resqai.model.IncidentDocument;
import com.resqai.repository.IncidentRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Incident persistence with idempotent uploads. The owner is ALWAYS derived
 * from the authenticated principal (passed in by the controller), never from
 * the request body. Replaying an upload with the same client incidentId and
 * owner returns the original record — never a duplicate.
 */
@Service
public class IncidentService {

    private final IncidentRepository incidents;

    public IncidentService(IncidentRepository incidents) {
        this.incidents = incidents;
    }

    public IncidentDtos.IncidentResponse create(String ownerUserId, IncidentDtos.IncidentRequest request) {
        Optional<IncidentDocument> existing =
            incidents.findByIncidentIdAndOwnerUserId(request.incidentId, ownerUserId);
        if (existing.isPresent()) {
            IncidentDtos.IncidentResponse response = toResponse(existing.get());
            response.duplicate = true;
            return response;
        }

        IncidentDocument incident = new IncidentDocument();
        applyRequest(incident, request);
        incident.setIncidentId(request.incidentId);
        incident.setOwnerUserId(ownerUserId);
        incident.setStatus("OPEN");
        incident.setServerCreatedAt(Instant.now());
        incident.setServerUpdatedAt(incident.getServerCreatedAt());
        IncidentDocument saved = incidents.save(incident);

        IncidentDtos.IncidentResponse response = toResponse(saved);
        response.duplicate = false;
        return response;
    }

    public IncidentDtos.IncidentResponse get(String ownerUserId, String incidentId) {
        IncidentDocument incident = incidents.findByIncidentIdAndOwnerUserId(incidentId, ownerUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
        return toResponse(incident);
    }

    public Page<IncidentDtos.IncidentResponse> mine(String ownerUserId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return incidents.findByOwnerUserIdOrderByClientCreatedAtDesc(ownerUserId, pageable)
            .map(this::toResponse);
    }

    /**
     * Batch synchronization. Every item is processed independently and
     * idempotently; one rejected item never blocks the others.
     */
    public IncidentDtos.BatchSyncResponse syncBatch(String ownerUserId, List<IncidentDtos.IncidentRequest> batch) {
        IncidentDtos.BatchSyncResponse response = new IncidentDtos.BatchSyncResponse();
        response.results = new ArrayList<>();
        for (IncidentDtos.IncidentRequest request : batch) {
            try {
                if (request == null || request.incidentId == null || request.incidentId.isBlank()) {
                    response.results.add(new IncidentDtos.BatchResultItem(
                        request == null ? null : request.incidentId, "REJECTED"));
                    response.results.get(response.results.size() - 1).error = "incidentId is required";
                    response.rejected++;
                    continue;
                }
                IncidentDtos.IncidentResponse created = create(ownerUserId, request);
                if (created.duplicate) {
                    IncidentDtos.BatchResultItem item = new IncidentDtos.BatchResultItem(
                        created.incidentId, "DUPLICATE");
                    item.id = created.id;
                    item.serverCreatedAt = created.serverCreatedAt;
                    response.results.add(item);
                    response.duplicates++;
                } else {
                    IncidentDtos.BatchResultItem item = new IncidentDtos.BatchResultItem(
                        created.incidentId, "CREATED");
                    item.id = created.id;
                    item.serverCreatedAt = created.serverCreatedAt;
                    response.results.add(item);
                    response.created++;
                }
            } catch (ResponseStatusException e) {
                IncidentDtos.BatchResultItem item = new IncidentDtos.BatchResultItem(
                    request.incidentId, "REJECTED");
                item.error = e.getReason() != null ? e.getReason() : "rejected";
                response.results.add(item);
                response.rejected++;
            } catch (RuntimeException e) {
                IncidentDtos.BatchResultItem item = new IncidentDtos.BatchResultItem(
                    request.incidentId, "REJECTED");
                item.error = "invalid payload";
                response.results.add(item);
                response.rejected++;
            }
        }
        return response;
    }

    private void applyRequest(IncidentDocument incident, IncidentDtos.IncidentRequest request) {
        incident.setCategory(request.category.trim());
        incident.setClientCreatedAt(request.clientCreatedAt != null
            ? Instant.ofEpochMilli(request.clientCreatedAt) : Instant.now());
        incident.setIdempotencyKey(request.idempotencyKey);
        incident.setSource(request.source);
        incident.setVictimEmergencyId(request.victimEmergencyId);
        incident.setDescription(request.description);
        incident.setLatitude(request.latitude);
        incident.setLongitude(request.longitude);
        incident.setLocationAccuracyMeters(request.locationAccuracyMeters);
        incident.setLocationCapturedAt(request.locationCapturedAt != null
            ? Instant.ofEpochMilli(request.locationCapturedAt) : null);
    }

    private IncidentDtos.IncidentResponse toResponse(IncidentDocument incident) {
        IncidentDtos.IncidentResponse response = new IncidentDtos.IncidentResponse();
        response.id = incident.getId();
        response.incidentId = incident.getIncidentId();
        response.ownerUserId = incident.getOwnerUserId();
        response.status = incident.getStatus();
        response.category = incident.getCategory();
        response.clientCreatedAt = incident.getClientCreatedAt() != null
            ? incident.getClientCreatedAt().toEpochMilli() : null;
        response.latitude = incident.getLatitude();
        response.longitude = incident.getLongitude();
        response.locationAccuracyMeters = incident.getLocationAccuracyMeters();
        response.locationCapturedAt = incident.getLocationCapturedAt() != null
            ? incident.getLocationCapturedAt().toEpochMilli() : null;
        response.source = incident.getSource();
        response.victimEmergencyId = incident.getVictimEmergencyId();
        response.description = incident.getDescription();
        response.serverCreatedAt = incident.getServerCreatedAt() != null
            ? incident.getServerCreatedAt().toEpochMilli() : null;
        return response;
    }
}
