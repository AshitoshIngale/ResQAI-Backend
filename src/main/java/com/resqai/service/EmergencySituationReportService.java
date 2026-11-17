package com.resqai.service;

import com.resqai.config.TwilioConfigurationService;
import com.resqai.model.EmergencyConditionTimelineDocument;
import com.resqai.model.EmergencySituationReportDocument;
import com.resqai.model.EmergencyVoiceSessionDocument;
import com.resqai.model.EmergencyFollowUpJobDocument;
import com.resqai.repository.EmergencyConditionTimelineRepository;
import com.resqai.repository.EmergencySituationReportRepository;
import com.resqai.repository.EmergencyVoiceSessionRepository;
import com.resqai.repository.EmergencyFollowUpJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Generates the structured emergency situation report (spec PART 8) from the
 * AI conversation facts, persists it, and relays it to the ambulance through
 * the existing Twilio SMS channel. Also owns the condition timeline
 * (PART 6/9): every observation, instruction and status change is recorded
 * with its honest source. DELIVERY_CONFIRMED is only set on a real Twilio
 * status callback - never claimed otherwise.
 */
@Service
public class EmergencySituationReportService {

    private static final Logger log = LoggerFactory.getLogger(EmergencySituationReportService.class);

    private final EmergencySituationReportRepository reports;
    private final EmergencyConditionTimelineRepository timeline;
    private final EmergencyVoiceSessionRepository voiceSessions;
    private final EmergencyFollowUpJobRepository followUpJobs;
    private final TwilioAlertService twilioAlertService;
    private final TwilioConfigurationService twilioConfig;

    public EmergencySituationReportService(
            EmergencySituationReportRepository reports,
            EmergencyConditionTimelineRepository timeline,
            EmergencyVoiceSessionRepository voiceSessions,
            EmergencyFollowUpJobRepository followUpJobs,
            TwilioAlertService twilioAlertService,
            TwilioConfigurationService twilioConfig) {
        this.reports = reports;
        this.timeline = timeline;
        this.voiceSessions = voiceSessions;
        this.followUpJobs = followUpJobs;
        this.twilioAlertService = twilioAlertService;
        this.twilioConfig = twilioConfig;
    }

    /** The ambulance destination used for situation-report relays. */
    public String ambulanceDestination() {
        return twilioConfig.ambulanceDestination(
            System.getenv().getOrDefault("EMERGENCY_CALL_DESTINATION", "+917676941547"));
    }

    /** Appends one entry to the incident's condition timeline (aggregate doc). */
    public void recordTimeline(String incidentId, String source,
                               String observation, boolean deterioration) {
        try {
            var doc = timeline.findByIncidentId(incidentId)
                .orElseGet(() -> {
                    var created = new EmergencyConditionTimelineDocument();
                    created.setIncidentId(incidentId);
                    return timeline.save(created);
                });
            var entry = new EmergencyConditionTimelineDocument.Entry(
                source, observation, new LinkedHashMap<>(), deterioration);
            doc.getEntries().add(entry);
            doc.setUpdatedAt(Instant.now());
            timeline.save(doc);
        } catch (Exception e) {
            log.warn("timeline write failed: {}", e.getMessage());
        }
    }

    /**
     * Builds the structured report text from the session facts and
     * observations (spec PART 8): initial and latest condition, first-aid
     * actions, medical warnings, deterioration, timestamp and source.
     */
    public String buildReportText(EmergencyVoiceSessionDocument session) {
        Map<String, String> f = session.getCollectedFacts() == null
            ? Map.of() : session.getCollectedFacts();
        StringBuilder sb = new StringBuilder();
        sb.append("ResQAI situation report (AI assistant, bystander-reported, ")
          .append("not clinically verified). ");
        sb.append("Patient condition: conscious=")
          .append(f.getOrDefault("conscious", "unknown"))
          .append("; breathing=").append(f.getOrDefault("breathing", "unknown"))
          .append("; bleeding=").append(f.getOrDefault("bleeding", "not reported"))
          .append("; seizure=").append(f.getOrDefault("seizure", "not reported"))
          .append("; chest pain=").append(f.getOrDefault("chestPain", "not reported"))
          .append(". ");
        if ("yes".equals(f.get("cprGiven"))) {
            sb.append("CPR guidance was provided by the assistant. ");
        }
        if (!session.getObservations().isEmpty()) {
            sb.append("Bystander statements: ");
            int last = Math.min(3, session.getObservations().size());
            int start = session.getObservations().size() - last;
            for (int i = start; i < session.getObservations().size(); i++) {
                sb.append('"').append(session.getObservations().get(i)).append("\"; ");
            }
        }
        if (session.getLatitude() != null && session.getLongitude() != null) {
            sb.append(String.format("GPS: %.4f,%.4f. ", session.getLatitude(), session.getLongitude()));
        } else {
            sb.append("GPS: not available. ");
        }
        sb.append("Generated ").append(Instant.now()).append(".");
        return sb.toString();
    }

    /** Persists a report (GENERATED) with idempotency per incident+kind. */
    public EmergencySituationReportDocument generate(
            EmergencyVoiceSessionDocument session, String kind, boolean urgent) {
        var report = new EmergencySituationReportDocument();
        report.setReportId(UUID.randomUUID().toString());
        report.setIncidentId(session.getIncidentId());
        report.setKind(kind);
        report.setReportText(buildReportText(session));
        report.setStructuredFactsJson(toJson(session.getCollectedFacts()));
        report.setVictimEmergencyId(session.getVictimEmergencyId());
        report.setReportIdempotencyKey(session.getIncidentId() + ":" + kind
            + (urgent ? ":" + UUID.randomUUID().toString().substring(0, 8) : ""));
        if (urgent) session.setUrgentFlagged(true);
        voiceSessions.save(session);
        return reports.save(report);
    }

    /** Relays the report through the existing Twilio SMS channel. */
    public EmergencySituationReportDocument relay(EmergencySituationReportDocument report,
                                                  String ambulanceDestination) {
        var result = twilioAlertService.sendSmsUpdate(ambulanceDestination, report.getReportText());
        if (result.sent()) {
            report.setStatus("SENT");
            report.setDeliveredAt(Instant.now());
            // DELIVERY_CONFIRMED is set ONLY by a Twilio status callback.
        } else {
            report.setStatus("FAILED");
            report.setDeliveryDetail(result.detail());
        }
        return reports.save(report);
    }

    /** Persists the scheduler jobs for a new AI session (restart-survivable). */
    public void scheduleFollowUps(EmergencyVoiceSessionDocument session) {
        try {
            Instant now = Instant.now();
            var fiveMin = new EmergencyFollowUpJobDocument();
            fiveMin.setIncidentId(session.getIncidentId());
            fiveMin.setType(EmergencyFollowUpJobDocument.TYPE_FIVE_MINUTE);
            fiveMin.setSessionId(session.getSessionId());
            fiveMin.setDueAt(now.plusSeconds(300));
            fiveMin.setIdempotencyKey(session.getIncidentId() + ":FIVE_MINUTE_REPORT");
            followUpJobs.save(fiveMin);
            var twentyMin = new EmergencyFollowUpJobDocument();
            twentyMin.setIncidentId(session.getIncidentId());
            twentyMin.setType(EmergencyFollowUpJobDocument.TYPE_TWENTY_MINUTE);
            twentyMin.setSessionId(session.getSessionId());
            twentyMin.setDueAt(now.plusSeconds(1200));
            twentyMin.setIdempotencyKey(session.getIncidentId() + ":TWENTY_MIN_FOLLOWUP");
            followUpJobs.save(twentyMin);
        } catch (Exception e) {
            log.warn("follow-up job scheduling failed: {}", e.getMessage());
        }
    }

    private String toJson(Map<String, String> facts) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(facts);
        } catch (Exception e) {
            return "{}";
        }
    }
}
