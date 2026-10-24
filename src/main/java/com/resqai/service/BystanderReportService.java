package com.resqai.service;

import com.resqai.dto.BystanderDtos;
import com.resqai.model.BystanderReportDocument;
import com.resqai.repository.BystanderReportRepository;
import com.resqai.repository.EmergencyContactRepository;
import com.resqai.repository.MedicalAccessAuditRepository;
import com.resqai.repository.EmergencyProfileRepository;

import com.resqai.model.IncidentDocument;
import com.resqai.repository.IncidentRepository;
import com.resqai.model.UserDocument;
import com.resqai.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bystander reporting logic.
 *
 * Privacy policy (documented, not invented): resolving an emergency code
 * confirms only that the code belongs to a real verified account and yields
 * its display Emergency ID. NO medical details are disclosed to bystanders —
 * an authorized responder-disclosure policy is PENDING (future phase), so
 * the summary deliberately carries no name, blood group, or health data.
 *
 * Matching: a report is linked to an existing incident only on sufficient
 * evidence — the victim's own display Emergency ID (strongest), or category
 * + time window + distance. Uncertain matches stay UNMATCHED. Reports are
 * never discarded for resembling another report, and storing a report never
 * implies an ambulance was dispatched.
 */
@Service
public class BystanderReportService {

    private static final long MATCH_TIME_WINDOW_MINUTES = 15;
    private static final double MATCH_DISTANCE_METERS = 500.0;

    private final BystanderReportRepository reports;
    private final UserRepository users;
    private final IncidentRepository incidents;
    private final EmergencyProfileRepository profiles;
    private final EmergencyContactRepository contacts;
    private final TwilioAlertService twilio;
    private final TwilioVoiceService voice;
    private final MedicalAccessAuditRepository audit;
    private final EmergencyCallScriptService scriptService;
    private final SarvamTtsService ttsService;
    private final ReverseGeocodeService geocoder;

    /** Destination for the automated emergency call (server-managed, env-only). */
    @org.springframework.beans.factory.annotation.Value("${resqai.emergency-call.destination:}")
    private String emergencyCallDestination;

    /** Public base URL for Twilio webhook callbacks (TwiML/TTS/status). */
    @org.springframework.beans.factory.annotation.Value("${resqai.public-base-url:}")
    private String publicBaseUrl;

    public BystanderReportService(BystanderReportRepository reports,
                                  UserRepository users,
                                  IncidentRepository incidents,
                                  EmergencyProfileRepository profiles,
                                  EmergencyContactRepository contacts,
                                  TwilioAlertService twilio,
                                  TwilioVoiceService voice,
                                  MedicalAccessAuditRepository audit,
                                  EmergencyCallScriptService scriptService,
                                  SarvamTtsService ttsService,
                                  ReverseGeocodeService geocoder) {
        this.reports = reports;
        this.users = users;
        this.incidents = incidents;
        this.profiles = profiles;
        this.contacts = contacts;
        this.twilio = twilio;
        this.voice = voice;
        this.audit = audit;
        this.scriptService = scriptService;
        this.ttsService = ttsService;
        this.geocoder = geocoder;
    }

    /**
     * Resolves an emergency code (bare, or RESQAI:1:<code> payload) to the
     * minimum authorized summary. Returns 404 with a generic message for
     * unknown/invalid codes — no enumeration, no profile details.
     */
    public BystanderDtos.SummaryResponse resolve(String rawCode) {
        String code = extractCode(rawCode);
        if (code == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Emergency ID not recognized");
        }
        Optional<UserDocument> user = users.findByEmergencyCode(code)
            .filter(UserDocument::isEnabled);
        if (user.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Emergency ID not recognized");
        }
        BystanderDtos.SummaryResponse response = new BystanderDtos.SummaryResponse();
        response.emergencyId = user.get().getEmergencyId();
        response.accountVerified = true;
        response.medicalInfoPolicy = "PENDING";
        return response;
    }

    /** Extracts and validates the bare code from a scanned or typed payload. */
    public String extractCode(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("RESQAI:")) {
            String[] parts = value.split(":", 3);
            if (parts.length != 3 || !"1".equals(parts[1])) return null; // unknown version
            value = parts[2].trim();
        }
        if (value.matches("^[A-Za-z0-9_-]{16,64}$")) return value;
        return null;
    }

    public BystanderDtos.ReportResponse create(BystanderDtos.ReportRequest request) {
        Optional<BystanderReportDocument> existing = reports.findByReportId(request.reportId);
        if (existing.isPresent()) {
            BystanderDtos.ReportResponse response = toResponse(existing.get());
            response.duplicate = true;
            return response;
        }

        // Resolve identity ONLY if the code is real; unverifiable stays honest.
        String code = extractCode(request.emergencyCode);
        if (code == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid emergency ID payload");
        }
        Optional<UserDocument> victim = users.findByEmergencyCode(code).filter(UserDocument::isEnabled);
        boolean verified = victim.isPresent();

        BystanderReportDocument report = new BystanderReportDocument();
        report.setReportId(request.reportId);
        report.setEmergencyCode(code);
        report.setVictimEmergencyId(victim.map(UserDocument::getEmergencyId).orElse(null));
        report.setIdentityVerification(verified ? "VERIFIED" : "UNVERIFIED");
        report.setCategory(request.category.trim());
        report.setStatus("RECEIVED");
        report.setDescription(request.description);
        report.setLatitude(request.latitude);
        report.setLongitude(request.longitude);
        report.setLocationAccuracyMeters(request.locationAccuracyMeters);
        report.setLocationCapturedAt(request.locationCapturedAt != null
            ? Instant.ofEpochMilli(request.locationCapturedAt) : null);
        report.setClientCreatedAt(request.clientCreatedAt != null
            ? Instant.ofEpochMilli(request.clientCreatedAt) : Instant.now());
        report.setSource(request.source);
        report.setServerCreatedAt(Instant.now());
        report.setServerUpdatedAt(report.getServerCreatedAt());

        // Persist the incident context BEFORE any external provider call so a
        // provider outage can never lose the report, and the reportId exists
        // for idempotent retries.
        BystanderReportDocument saved = reports.save(report);
        // Resolve a human-readable place (area, city, pincode) ONCE and
        // persist it: the spoken script needs it and every later reader
        // (webhook, retries) must see the SAME name for cache consistency.
        if (saved.getLatitude() != null && saved.getLongitude() != null) {
            try {
                saved.setLocationName(geocoder.resolvePlaceName(
                    saved.getLatitude(), saved.getLongitude()));
            } catch (Exception e) {
                // Speech falls back to spoken coordinates; never blocks.
            }
        }
        if (verified) {
            attemptAssociation(saved, victim.get());
            notifyVictimContacts(saved, victim.get());
            initiateEmergencyCallOnce(saved, victim.get());
            saved = reports.save(saved);
        }
        BystanderDtos.ReportResponse response = toResponse(saved);
        response.duplicate = false;
        return response;
    }

    /**
     * Builds the MINIMUM authorized medical summary from the server's own
     * record (client values are never trusted). Missing fields stay null -
     * nothing is invented. Every disclosure is audited.
     */
    public BystanderDtos.VictimSummary buildAuthorizedSummary(
            BystanderReportDocument report, UserDocument victim) {
        BystanderDtos.VictimSummary summary = loadAuthorizedFields(victim);
        try {
            audit.save(new com.resqai.model.MedicalAccessAuditDocument(
                report.getReportId(), report.getEmergencyCode(),
                "BYSTANDER_REPORT_DISCLOSURE"));
        } catch (Exception e) {
            // Audit failure must not block the emergency flow; it is logged
            // without any medical content.
            org.slf4j.LoggerFactory.getLogger(BystanderReportService.class)
                .warn("medical access audit write failed: {}", e.getMessage());
        }
        return summary;
    }

    /**
     * Places the automated emergency call AT MOST ONCE per report
     * (callStatus null-check = idempotency guard). The destination is the
     * central server-managed emergency number - never a personal contact.
     * The honest provider outcome is persisted; INITIATED only means Twilio
     * accepted the call, never that an ambulance was dispatched.
     */
    public void initiateEmergencyCallOnce(BystanderReportDocument report, UserDocument victim) {
        if (report.getCallStatus() != null) return; // already attempted - no duplicate calls
        try {
            String destination = emergencyCallDestination == null
                ? "" : emergencyCallDestination.trim();
            if (!TwilioVoiceService.isValidDestination(destination)) {
                report.setCallStatus("FAILED");
                report.setCallDetail("Emergency call destination is not configured "
                    + "or invalid on the server; no call was placed.");
                return;
            }
            if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
                report.setCallStatus("FAILED");
                report.setCallDetail("Public base URL is not configured; "
                    + "Twilio webhook hosting is unavailable.");
                return;
            }
            String base = publicBaseUrl.replaceAll("/$", "");
            String twimlUrl = base + "/api/v1/twiml/incident/" + report.getReportId();
            String statusUrl = base + "/api/v1/twilio/status/" + report.getReportId();
            TwilioVoiceService.CallResult result =
                voice.placeEmergencyCall(destination, twimlUrl, statusUrl);
            report.setCallStatus(result.status().name());
            report.setCallDetail(result.detail());
            report.setCallSid(result.callSid());
            report.setCallAttemptedAt(result.attemptedAt());
            if (result.status() == TwilioVoiceService.Status.INITIATED) {
                // The phone rings for seconds before Twilio fetches TwiML -
                // warm the speech cache in parallel so the call plays the
                // synthesized audio instead of the <Say> fallback.
                prewarmSpeech(report, victim);
            }
        } catch (Exception e) {
            report.setCallStatus("FAILED");
            report.setCallDetail("call initiation error (details omitted)");
        }
    }

    /**
     * Loads the authorized medical fields WITHOUT auditing — for internal
     * speech preparation of an already-audited disclosure.
     */
    private BystanderDtos.VictimSummary loadAuthorizedFields(UserDocument victim) {
        BystanderDtos.VictimSummary summary = new BystanderDtos.VictimSummary();
        Optional<com.resqai.model.EmergencyProfileDocument> profile =
            profiles.findByUserId(victim.getId());
        profile.ifPresent(p -> {
            summary.bloodGroup = blankToNull(p.getBloodGroup());
            summary.allergies = nonEmpty(p.getAllergies());
            summary.medicalConditions = nonEmpty(p.getMedicalConditions());
            summary.currentMedications = nonEmpty(p.getCurrentMedications());
            if (p.getEmergencyContacts() != null && !p.getEmergencyContacts().isEmpty()) {
                summary.emergencyContactPhone = p.getEmergencyContacts().get(0).getPhone();
            }
        });
        summary.emergencyName = blankToNull(victim.getFullName());
        return summary;
    }

    /** Synthesizes all speech segments for this report in the background. */
    private void prewarmSpeech(BystanderReportDocument report, UserDocument victim) {
        try {
            BystanderDtos.VictimSummary summary = loadAuthorizedFields(victim);
            StateLanguageResolver.Resolution regional =
                StateLanguageResolver.resolve(report.getLatitude(), report.getLongitude());
            List<EmergencyCallScriptService.Segment> segments = scriptService.buildScript(
                report.getCategory(), report.getLocationName(),
                report.getLatitude(), report.getLongitude(),
                summary.emergencyName, summary.bloodGroup, summary.allergies,
                summary.medicalConditions, regional);
            ttsService.prewarm(segments.stream()
                .map(seg -> new String[]{seg.text(), seg.languageCode()})
                .toList());
        } catch (Exception e) {
            // Pre-warm is best-effort only; the webhook falls back to <Say>.
        }
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static java.util.List<String> nonEmpty(java.util.List<String> list) {
        if (list == null) return null;
        java.util.List<String> clean = list.stream()
            .filter(v -> v != null && !v.isBlank()).toList();
        return clean.isEmpty() ? null : clean;
    }

    /**
     * Notifies the victim's saved emergency contacts via Twilio and records
     * the REAL provider outcome on the report. Missing credentials produce
     * NOT_CONFIGURED — never a fabricated "contacts notified".
     */
    private void notifyVictimContacts(BystanderReportDocument report, UserDocument victim) {
        try {
            List<String> numbers = new ArrayList<>();
            contacts.findByOwnerUserIdOrderByCreatedAtAsc(victim.getId())
                .forEach(c -> numbers.add(c.getPhone()));
            profiles.findByUserId(victim.getId()).ifPresent(profile ->
                profile.getEmergencyContacts().forEach(c -> numbers.add(c.getPhone())));
            String locationText = report.getLatitude() != null && report.getLongitude() != null
                ? report.getLatitude() + "," + report.getLongitude() : null;
            TwilioAlertService.NotificationResult result =
                twilio.notifyEmergencyContacts(victim.getFullName(), numbers, report.getCategory(), locationText);
            report.setNotificationStatus(result.status().name());
            report.setNotificationDetail(TwilioAlertService.persistableSummary(result));
            report.setNotificationAttemptedAt(result.attemptedAt());
        } catch (Exception e) {
            report.setNotificationStatus("FAILED");
            report.setNotificationDetail("notification error (details omitted)");
        }
    }

    /**
     * Evidence-based association with the victim's own incidents. Signals:
     * victim identity + same category + time window + distance. Weak evidence
     * leaves the report UNMATCHED — never silently discarded or force-merged.
     */
    private void attemptAssociation(BystanderReportDocument report, UserDocument victim) {
        if (victim.getEmergencyId() == null) return;

        var page = incidents.findByOwnerUserIdOrderByClientCreatedAtDesc(
            victim.getId(), PageRequest.of(0, 20));
        Instant reportTime = report.getClientCreatedAt();
        for (IncidentDocument candidate : page.getContent()) {
            if (candidate.getCategory() == null || !candidate.getCategory().equals(report.getCategory())) {
                continue;
            }
            Instant incidentTime = candidate.getClientCreatedAt();
            if (incidentTime == null) continue;
            long minutes = Math.abs(ChronoUnit.MINUTES.between(incidentTime, reportTime));
            boolean timeOk = minutes <= MATCH_TIME_WINDOW_MINUTES;
            boolean victimMatch = incidentMatchesVictim(candidate, victim.getEmergencyId());
            boolean distanceOk = withinDistance(candidate, report.getLatitude(), report.getLongitude());

            if (victimMatch && timeOk) {
                link(report, candidate, "VICTIM_ID");
                return;
            }
            if (timeOk && distanceOk && report.getLatitude() != null) {
                link(report, candidate, "TIME_PROXIMITY_CATEGORY");
                return;
            }
        }
        // No sufficient evidence: remains UNMATCHED and fully retained.
    }

    private boolean incidentMatchesVictim(IncidentDocument incident, String victimEmergencyId) {
        return victimEmergencyId != null && victimEmergencyId.equals(incident.getVictimEmergencyId());
    }

    private boolean withinDistance(IncidentDocument incident, Double lat, Double lon) {
        if (incident.getLatitude() == null || incident.getLongitude() == null || lat == null || lon == null) {
            return false;
        }
        return haversineMeters(lat, lon, incident.getLatitude(), incident.getLongitude()) <= MATCH_DISTANCE_METERS;
    }

    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private void link(BystanderReportDocument report, IncidentDocument incident, String signal) {
        report.setLinkedIncidentId(incident.getIncidentId());
        report.setMatchStatus("MATCHED");
        report.setMatchSignals(signal);
        report.setServerUpdatedAt(Instant.now());
    }

    private BystanderDtos.ReportResponse toResponse(BystanderReportDocument report) {
        BystanderDtos.ReportResponse response = new BystanderDtos.ReportResponse();
        response.reportId = report.getReportId();
        response.victimEmergencyId = report.getVictimEmergencyId();
        response.identityVerification = report.getIdentityVerification();
        response.status = report.getStatus();
        response.category = report.getCategory();
        response.clientCreatedAt = report.getClientCreatedAt() != null
            ? report.getClientCreatedAt().toEpochMilli() : null;
        response.linkedIncidentId = report.getLinkedIncidentId();
        response.matchStatus = report.getMatchStatus();
        response.matchSignals = report.getMatchSignals();
        response.serverCreatedAt = report.getServerCreatedAt() != null
            ? report.getServerCreatedAt().toEpochMilli() : null;
        response.notificationStatus = report.getNotificationStatus();
        response.voiceTranscript = report.getVoiceTranscript();
        response.callStatus = report.getCallStatus();
        response.callDetail = report.getCallDetail();
        if ("VERIFIED".equals(report.getIdentityVerification())) {
            users.findByEmergencyCode(report.getEmergencyCode())
                .filter(UserDocument::isEnabled)
                .ifPresent(victim -> response.victimSummary =
                    buildAuthorizedSummary(report, victim));
        }
        return response;
    }
}
