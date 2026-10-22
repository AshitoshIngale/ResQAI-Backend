package com.resqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Twilio notification (WhatsApp first, SMS fallback) to a victim's emergency
 * contacts when a verified bystander report arrives. Credentials live ONLY in
 * backend env vars — never in the app, never in logs. The WhatsApp channel
 * exists because trial accounts cannot send plain SMS from a number they do
 * not own; when a real Twilio number is configured the SMS path resumes.
 *
 * Honesty contract: the reported status reflects EXACTLY what Twilio
 * confirmed. Missing credentials -> NOT_CONFIGURED (reported, never faked).
 * A failed send is FAILED with the provider error, and is never presented
 * as an ambulance dispatch.
 */
@Service
public class TwilioAlertService {

    public enum Status { NOT_CONFIGURED, SENT, FAILED, INVALID_CREDENTIALS }

    public record NotificationResult(
        Status status,
        List<String> recipientsAttempted,
        String detail,
        Instant attemptedAt
    ) {}

    private static final Logger log = LoggerFactory.getLogger(TwilioAlertService.class);
    private static final String TWILIO_MESSAGES_URL =
        "https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json";

    private final ProviderHttpClient http;
    private final String accountSid;
    private final String authToken;
    private final String fromNumber;
    private final String whatsappFrom;

    public TwilioAlertService(ProviderHttpClient http,
                              @Value("${resqai.twilio.account-sid:}") String accountSid,
                              @Value("${resqai.twilio.auth-token:}") String authToken,
                              @Value("${resqai.twilio.from-number:}") String fromNumber) {
        this(http, accountSid, authToken, fromNumber, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TwilioAlertService(ProviderHttpClient http,
                              @Value("${resqai.twilio.account-sid:}") String accountSid,
                              @Value("${resqai.twilio.auth-token:}") String authToken,
                              @Value("${resqai.twilio.from-number:}") String fromNumber,
                              @Value("${resqai.twilio.whatsapp-from:}") String whatsappFrom) {
        this.http = http;
        this.accountSid = accountSid == null ? "" : accountSid.trim();
        this.authToken = authToken == null ? "" : authToken.trim();
        this.fromNumber = fromNumber == null ? "" : fromNumber.trim();
        this.whatsappFrom = whatsappFrom == null ? "" : whatsappFrom.trim();
    }

    /** True when a complete delivery channel is configured (WhatsApp preferred). */
    public boolean isConfigured() {
        return !accountSid.isBlank() && !authToken.isBlank()
            && (!whatsappFrom.isBlank() || !fromNumber.isBlank());
    }

    /**
     * Sends one SMS per contact. Returns the honest per-run result. The
     * message is limited to incident essentials — never a full medical record.
     */
    public NotificationResult notifyEmergencyContacts(String victimName, List<String> phoneNumbers,
                                                      String category, String locationText) {
        Instant attemptedAt = Instant.now();
        List<String> attempted = new ArrayList<>();
        if (!isConfigured()) {
            return new NotificationResult(Status.NOT_CONFIGURED, attempted,
                "Twilio credentials are not configured on the backend; contacts were not notified.", attemptedAt);
        }
        if (phoneNumbers == null || phoneNumbers.isEmpty()) {
            return new NotificationResult(Status.FAILED, attempted,
                "The victim profile has no emergency contact numbers to notify.", attemptedAt);
        }

        String body = "ResQAI emergency alert: a bystander reported '" + category + "' for "
            + (victimName == null ? "a person" : victimName) + ". "
            + (locationText == null || locationText.isBlank() ? "" : "Location: " + locationText + ". ")
            + "Please respond or call them now.";

        List<String> outcomes = new ArrayList<>();
        boolean anySent = false;
        boolean credentialProblem = false;
        boolean whatsappChannel = !whatsappFrom.isBlank();
        for (String raw : phoneNumbers) {
            String to = raw == null ? "" : raw.trim();
            if (to.isBlank()) continue;
            String wireFrom;
            String wireTo;
            if (whatsappChannel) {
                wireFrom = whatsappFrom;
                wireTo = to.startsWith("whatsapp:") ? to : "whatsapp:" + to;
            } else {
                wireFrom = fromNumber;
                wireTo = to;
            }
            attempted.add(to);
            try {
                String form = new StringBuilder()
                    .append("To=").append(urlEncode(wireTo))
                    .append("&From=").append(urlEncode(wireFrom))
                    .append("&Body=").append(urlEncode(body))
                    .toString();
                String auth = "Basic " + Base64.getEncoder().encodeToString(
                    (accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
                ProviderHttpClient.ProviderResponse response = http.send(
                    "POST", String.format(TWILIO_MESSAGES_URL, accountSid),
                    "application/x-www-form-urlencoded", auth, form.getBytes(StandardCharsets.UTF_8));
                if (response.statusCode >= 200 && response.statusCode < 300) {
                    anySent = true;
                    outcomes.add(to + "=SENT");
                } else if (response.statusCode == 401 || response.statusCode == 403) {
                    credentialProblem = true;
                    outcomes.add(to + "=INVALID_CREDENTIALS");
                } else {
                    // Surface Twilio's own error code (e.g. 21659 unowned From,
                    // 63015 WhatsApp sandbox window) — the honest failure detail.
                    String code = extractTwilioErrorCode(response.body);
                    outcomes.add(to + "=FAILED(" + response.statusCode
                        + (code.isEmpty() ? "" : ":" + code) + ")");
                }
            } catch (Exception e) {
                log.warn("Twilio notification to {} failed: {}", to, e.getMessage());
                outcomes.add(to + "=FAILED(network)");
            }
        }

        Status status = credentialProblem ? Status.INVALID_CREDENTIALS
            : (anySent ? Status.SENT : Status.FAILED);
        String detail = "channel=" + (whatsappChannel ? "whatsapp" : "sms")
            + "; contacts:" + outcomes + "; status=" + status;
        log.info("Twilio notification result: {}", detail);
        return new NotificationResult(status, attempted, detail, Instant.now());
    }

    /** Extracts Twilio's numeric error code from an error JSON body, if present. */
    private static String extractTwilioErrorCode(String body) {
        if (body == null) return "";
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            return node.has("code") ? String.valueOf(node.get("code").asInt()) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Compact, log-safe status summary persisted on the report document. */
    public static String persistableSummary(NotificationResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", result.status().name());
        map.put("recipients", result.recipientsAttempted().size());
        map.put("detail", result.detail());
        return map.toString();
    }
}
