package com.resqai.controller;

import com.resqai.config.TwilioConfigurationService;
import com.resqai.model.EmergencySmsIncidentDocument;
import com.resqai.repository.EmergencySmsIncidentRepository;
import com.resqai.service.EmergencySmsSecurityService;
import com.resqai.service.ProviderHttpClient;
import com.resqai.service.TwilioVoiceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * SMS-to-voice relay for the owner's OFFLINE SOS (spec PART 5).
 *
 * Flow: phone sends a SIGNED cellular SMS to Twilio Number A (no internet
 * needed on the phone) -> Twilio POSTs it here -> signature + sender +
 * device-signature + nonce + duplicate validation -> incident stored ->
 * ambulance call placed from Number A to the configured destination ->
 * TwiML served from /api/v1/twiml/sms/{incidentId}; status tracked at
 * /api/v1/twilio/status/sms/{incidentId}.
 */
@RestController
@RequestMapping("/api/emergency/sms")
public class TwilioSmsWebhookController {

    private final EmergencySmsSecurityService security;
    private final EmergencySmsIncidentRepository incidents;
    private final TwilioVoiceService voice;
    private final TwilioConfigurationService twilioConfig;

    @Value("${resqai.public-base-url:}")
    private String publicBaseUrl;

    public TwilioSmsWebhookController(EmergencySmsSecurityService security,
                                      EmergencySmsIncidentRepository incidents,
                                      TwilioVoiceService voice,
                                      TwilioConfigurationService twilioConfig) {
        this.security = security;
        this.incidents = incidents;
        this.voice = voice;
        this.twilioConfig = twilioConfig;
    }

    /** Inbound emergency SMS from Twilio Number A. */
    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> inboundSms(
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            @RequestParam Map<String, String> params) {

        if (!voice.shouldValidateSignatures()) return ResponseEntity.notFound().build();
        if (!signatureValid(signature, "/api/emergency/sms/webhook", params)) {
            return ResponseEntity.status(403)
                .contentType(MediaType.APPLICATION_XML)
                .body(xmlResponse("Invalid request."));
        }

        String from = params.getOrDefault("From", "");
        String bodyText = params.getOrDefault("Body", "");
        String[] fields = bodyText.split("\\|", -1);
        String signatureB64 = params.getOrDefault("Sig", "");

        EmergencySmsSecurityService.ValidationResult validation =
            security.validate(from, fields, signatureB64);
        if (!validation.valid()) {
            // Honest rejection: never treat an unauthenticated SOS as valid.
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xmlResponse("ResQAI: this emergency message could not be authenticated."));
        }

        String incidentId = fields[1];
        if (incidents.existsByIncidentId(incidentId)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
                .body(xmlResponse("ResQAI: this emergency was already received and processed."));
        }

        EmergencySmsIncidentDocument incident = new EmergencySmsIncidentDocument();
        incident.setIncidentId(incidentId);
        incident.setPatientId(fields[2]);
        incident.setDeviceId(fields[3]);
        incident.setEmergencyCategory(fieldOrNull(fields, 6));
        incident.setPatientName(fieldOrNull(fields, 7));
        incident.setBloodGroup(fieldOrNull(fields, 8));
        incident.setAllergies(fieldOrNull(fields, 9));
        incident.setMedicalConditions(fieldOrNull(fields, 10));
        try { incident.setLatitude(Double.parseDouble(fieldOrNull(fields, 11))); } catch (Exception e) { /* honest absence */ }
        try { incident.setLongitude(Double.parseDouble(fieldOrNull(fields, 12))); } catch (Exception e) { /* honest absence */ }
        try { incident.setLocationAccuracyMeters(Double.parseDouble(fieldOrNull(fields, 13))); } catch (Exception e) { /* honest absence */ }
        incident.setPreferredLanguage(fieldOrNull(fields, 14));
        incident.setSenderNumber(from);
        incident.setValidationStatus("VALID");
        incidents.save(incident);

        // Ambulance call from Number A to the configured destination.
        placeAmbulanceCall(incident);

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
            .body(xmlResponse("ResQAI emergency received. The ambulance alert call is being placed."));
    }

    /** Twilio status callback for the SMS-triggered ambulance call. */
    @PostMapping(value = "/status/{incidentId}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> callStatus(
            @PathVariable String incidentId,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            @RequestParam Map<String, String> params) {
        if (!voice.shouldValidateSignatures()) return ResponseEntity.notFound().build();
        if (!signatureValid(signature, "/api/emergency/sms/status/" + incidentId, params)) {
            return ResponseEntity.status(403).build();
        }
        EmergencySmsIncidentDocument incident = incidents.findByIncidentId(incidentId);
        if (incident != null) {
            String status = params.getOrDefault("CallStatus", "unknown");
            incident.setCallStatus(status);
            if ("in-progress".equalsIgnoreCase(status)) {
                incident.setCallAnsweredAt(Instant.now());
            }
            if ("completed".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)
                || "busy".equalsIgnoreCase(status) || "no-answer".equalsIgnoreCase(status)) {
                incident.setCallCompletedAt(Instant.now());
                if ("failed".equalsIgnoreCase(status)) {
                    incident.setFailureReason(params.getOrDefault("ErrorMessage", "call failed"));
                }
            }
            incidents.save(incident);
        }
        return ResponseEntity.ok().build();
    }

    // ---- internals ----

    private void placeAmbulanceCall(EmergencySmsIncidentDocument incident) {
        try {
            String destination = twilioConfig.ambulanceDestination(
                java.util.Optional.ofNullable(System.getenv("EMERGENCY_CALL_DESTINATION"))
                    .orElse("+917676941547"));
            String from = twilioConfig.ambulanceCallerId();
            String base = twilioConfig.publicBaseUrl();
            String twimlUrl = base + "/api/v1/twiml/sms/" + incident.getIncidentId();
            String statusUrl = base + "/api/emergency/sms/status/" + incident.getIncidentId();

            // placeEmergencyCall uses the voice service's From resolution;
            // Number A takes priority when configured.
            TwilioVoiceService.CallResult result;
            if (from != null) {
                result = voice.placeEmergencyCallFrom(destination, from, twimlUrl, statusUrl);
            } else {
                result = voice.placeEmergencyCall(destination, twimlUrl, statusUrl);
            }
            incident.setCallSid(result.callSid());
            incident.setCallStatus(result.status().name());
            incident.setCallDetail(result.detail());
            incident.setCallInitiatedAt(result.attemptedAt());
            incidents.save(incident);
        } catch (Exception e) {
            incident.setCallStatus("FAILED");
            incident.setFailureReason("call initiation error (details omitted)");
            incidents.save(incident);
        }
    }

    private boolean signatureValid(String signature, String path, Map<String, String> params) {
        Map<String, String> all = params == null ? new HashMap<>() : params;
        String base = (publicBaseUrl == null || publicBaseUrl.isBlank())
            ? "https://resqai-backend-n128.onrender.com" : publicBaseUrl.replaceAll("/$", "");
        return voice.isSignatureValid(signature, base + path, all);
    }

    private static String fieldOrNull(String[] fields, int idx) {
        return idx < fields.length ? fields[idx] : null;
    }

    private static String xmlResponse(String message) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Response><Message>"
            + message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            + "</Message></Response>";
    }
}
