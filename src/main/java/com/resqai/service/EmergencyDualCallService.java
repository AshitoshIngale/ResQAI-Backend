package com.resqai.service;

import com.resqai.config.TwilioConfigurationService;
import com.resqai.model.BystanderReportDocument;
import com.resqai.model.UserDocument;
import com.resqai.repository.BystanderReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dispatches the TWO independent outbound calls for a verified bystander
 * report (spec PART 6):
 *   CALL 1: ambulance (Number A / auto-detected From) - existing logic.
 *   CALL 2: bystander AI first-aid call (Number B -> bystander's number).
 * Both start concurrently on separate threads; neither waits for the other
 * and one failing never cancels the other. Idempotent per report: ambulance
 * via callStatus null-check, bystander via bystanderCallSid null-check.
 */
@Service
public class EmergencyDualCallService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyDualCallService.class);

    private final BystanderReportRepository reports;
    private final TwilioConfigurationService twilioConfig;
    private final TwilioVoiceService voice;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "resqai-dual-call");
        t.setDaemon(true);
        return t;
    });

    public EmergencyDualCallService(BystanderReportRepository reports,
                                    TwilioConfigurationService twilioConfig,
                                    TwilioVoiceService voice) {
        this.reports = reports;
        this.twilioConfig = twilioConfig;
        this.voice = voice;
    }

    /**
     * Fire-and-forget: the AMBULANCE call dispatches immediately (async).
     *
     * ARCHITECTURE CHANGE (latest spec): the bystander AI conversation is now
     * a BROWSER/NATIVE in-app voice session over WebSocket - NOT a Twilio
     * telephone call to the bystander. The bystander starts it from the app
     * (POST /api/emergency/{incidentId}/voice/session + WS connect). The
     * placeBystanderAICall path remains in TwilioVoiceService but is no
     * longer dispatched here.
     */
    public void startDualCallAsync(BystanderReportDocument report, UserDocument victim) {
        executor.submit(() -> startAmbulanceCall(report, victim));
        log.info("ambulance call dispatched async; bystander AI conversation is "
            + "browser-based (in-app voice session), not a Twilio phone call");
    }

    /** CALL 1: ambulance - delegates to the existing idempotent initiation. */
    private void startAmbulanceCall(BystanderReportDocument report, UserDocument victim) {
        try {
            BystanderReportService instance = reportService;
            if (instance != null) {
                instance.initiateEmergencyCallOnce(report, victim);
            }
        } catch (Exception e) {
            log.warn("ambulance call dispatch failed: {}", e.getMessage());
        }
    }

    /** CALL 2: bystander AI call from Number B. Independent of CALL 1. */
    private void startBystanderAICall(BystanderReportDocument report) {
        try {
            if (report.getBystanderCallSid() != null) return; // idempotent
            String callerId = twilioConfig.bystanderAiCallerId();
            if (callerId == null) {
                report.setBystanderCallStatus("NOT_CONFIGURED");
                report.setBystanderCallDetail("Twilio Number B (bystander AI) is not configured.");
                reports.save(report);
                return;
            }
            String destination = twilioConfig.bystanderDestination(report.getBystanderPhone());
            String base = twilioConfig.publicBaseUrl();
            String twimlUrl = base + "/api/v1/twiml/bystander/" + report.getReportId();
            String statusUrl = base + "/api/v1/twilio/status/bystander/" + report.getReportId();
            // Number B lives on ACCOUNT B: its own credentials authenticate.
            TwilioVoiceService.CallResult result =
                voice.placeBystanderAICall(destination, callerId, twimlUrl, statusUrl);
            report.setBystanderCallSid(result.callSid());
            report.setBystanderCallStatus(result.status().name());
            report.setBystanderCallDetail(result.detail());
            report.setBystanderCallAttemptedAt(Instant.now());
            reports.save(report);
            log.info("bystander AI call dispatched: {} -> {}", callerId, destination);
        } catch (Exception e) {
            log.warn("bystander AI call dispatch failed: {}", e.getMessage());
            try {
                report.setBystanderCallStatus("FAILED");
                report.setBystanderCallDetail("bystander call initiation error (details omitted)");
                reports.save(report);
            } catch (Exception ignore) { /* report may be gone */ }
        }
    }

    /** Injected by BystanderReportService to avoid a circular dependency. */
    private volatile BystanderReportService reportService;
    public void setReportService(BystanderReportService service) { this.reportService = service; }
}
