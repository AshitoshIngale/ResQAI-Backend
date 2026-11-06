package com.resqai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * THE centralized Twilio configuration (spec PART 3). No controller or
 * service may read Twilio numbers from anywhere else.
 *
 * Two-number architecture:
 *   Number A (SMS_AMBULANCE): receives the owner's emergency SMS and places
 *                             the ambulance voice call.
 *   Number B (BYSTANDER_AI):  places the bystander's real-time AI call.
 * Test destinations are separate configurable numbers, never hardcoded.
 *
 * Startup validation queries Twilio for both numbers' presence and
 * capabilities and logs a clear configuration report - failures never crash
 * the app (features honestly report NOT_CONFIGURED) but are impossible to miss.
 */
@Service
public class TwilioConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(TwilioConfigurationService.class);

    @Value("${resqai.twilio.account-sid:}") private String accountSid;
    @Value("${resqai.twilio.auth-token:}") private String authToken;

    @Value("${resqai.twilio.sms-ambulance-number:}") private String smsAmbulanceNumber;
    @Value("${resqai.twilio.bystander-ai-number:}") private String bystanderAiNumber;

    // Number B may live on a SECOND Twilio account (the free tier includes
    // one free number per account) - so the bystander AI call authenticates
    // against its own account credentials.
    @Value("${resqai.twilio.account-sid-b:}") private String accountSidB;
    @Value("${resqai.twilio.auth-token-b:}") private String authTokenB;

    @Value("${resqai.twilio.ambulance-test-destination:}") private String ambulanceTestDestination;
    @Value("${resqai.twilio.bystander-test-destination:}") private String bystanderTestDestination;

    @Value("${resqai.public-base-url:}") private String publicBaseUrl;

    private final com.resqai.service.ProviderHttpClient http;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Startup validation report, also exposed via /api/v1/config/twilio-status. */
    private volatile String lastValidationReport = "not yet validated";

    public TwilioConfigurationService(com.resqai.service.ProviderHttpClient http) {
        this.http = http;
    }

    public boolean isConfigured() {
        return !accountSid.isBlank() && !authToken.isBlank();
    }

    /** Caller ID for the AMBULANCE call: Number A, or the legacy voice-from. */
    public String ambulanceCallerId() {
        if (!smsAmbulanceNumber.isBlank()) return smsAmbulanceNumber.trim();
        return null; // callers fall back to their own configured From / auto-detect
    }

    /** Caller ID for the BYSTANDER AI call: Number B only (strict). */
    public String bystanderAiCallerId() {
        return bystanderAiNumber.isBlank() ? null : bystanderAiNumber.trim();
    }

    /** Account B credentials (the account that owns Number B). */
    public String accountSidB() {
        return accountSidB == null ? "" : accountSidB.trim();
    }

    public String authTokenB() {
        return authTokenB == null ? "" : authTokenB.trim();
    }

    /** True when Number B AND its owning account are fully configured. */
    public boolean isBystanderAccountConfigured() {
        return !accountSidB().isBlank() && !authTokenB().isBlank()
            && !bystanderAiCallerId().isBlank();
    }

    /** Auth token used to validate bystander-call webhooks (account B). */
    public String bystanderAuthToken() {
        return authTokenB().isBlank() ? authToken : authTokenB();
    }

    /** Ambulance destination: test destination during testing, else configured. */
    public String ambulanceDestination(String configured) {
        return ambulanceTestDestination.isBlank() ? configured : ambulanceTestDestination.trim();
    }

    /** Bystander AI destination: test destination during testing, else the bystander's number. */
    public String bystanderDestination(String bystanderNumber) {
        return bystanderTestDestination.isBlank() ? bystanderNumber : bystanderTestDestination.trim();
    }

    public String publicBaseUrl() {
        return (publicBaseUrl == null || publicBaseUrl.isBlank())
            ? "https://resqai-backend-n128.onrender.com" : publicBaseUrl.replaceAll("/$", "");
    }

    /** The number the owner's phone sends its emergency SMS to (Number A). */
    public String smsTriggerNumber() {
        return smsAmbulanceNumber.isBlank() ? null : smsAmbulanceNumber.trim();
    }

    public String lastValidationReport() {
        return lastValidationReport;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateOnStartup() {
        validateAsync();
    }

    /** Async startup validation against the live Twilio account. */
    public void validateAsync() {
        Thread t = new Thread(this::validateNow, "twilio-config-validation");
        t.setDaemon(true);
        t.start();
    }

    void validateNow() {
        StringBuilder report = new StringBuilder();
        if (!isConfigured()) {
            lastValidationReport = "Twilio credentials are not configured.";
            log.warn("Twilio config validation: {}", lastValidationReport);
            return;
        }
        try {
            String url = "https://api.twilio.com/2010-04-01/Accounts/" + accountSid
                + "/IncomingPhoneNumbers.json?PageSize=50";
            String auth = "Basic " + java.util.Base64.getEncoder().encodeToString(
                (accountSid + ":" + authToken).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            com.resqai.service.ProviderHttpClient.ProviderResponse response = http.send(
                "GET", url, "application/json", auth, new byte[0]);
            if (response.statusCode < 200 || response.statusCode >= 300) {
                lastValidationReport = "Twilio account query failed (HTTP " + response.statusCode + ").";
                log.warn("Twilio config validation: {}", lastValidationReport);
                return;
            }
            com.fasterxml.jackson.databind.JsonNode numbers = mapper.readTree(response.body)
                .path("incoming_phone_numbers");
            List<String> owned = new java.util.ArrayList<>();
            numbers.forEach(n -> owned.add(n.path("phone_number").asText()));

            checkNumber(report, "A (SMS + ambulance voice)", smsAmbulanceNumber, owned);
            if (!accountSidB().isBlank() && accountSidB() != accountSid) {
                // Number B lives on its own account - validate against THAT account.
                String authB = "Basic " + java.util.Base64.getEncoder().encodeToString(
                    (accountSidB() + ":" + authTokenB()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                com.resqai.service.ProviderHttpClient.ProviderResponse responseB = http.send(
                    "GET", "https://api.twilio.com/2010-04-01/Accounts/" + accountSidB()
                        + "/IncomingPhoneNumbers.json?PageSize=50",
                    "application/json", authB, new byte[0]);
                if (responseB.statusCode >= 200 && responseB.statusCode < 300) {
                    List<String> ownedB = new java.util.ArrayList<>();
                    mapper.readTree(responseB.body).path("incoming_phone_numbers")
                        .forEach(n -> ownedB.add(n.path("phone_number").asText()));
                    checkNumber(report, "B (bystander AI voice, account B)",
                        bystanderAiNumber, ownedB);
                } else {
                    report.append("B: account B credentials rejected (HTTP ")
                          .append(responseB.statusCode).append("). ");
                }
            } else {
                checkNumber(report, "B (bystander AI voice)", bystanderAiNumber, owned);
            }
            if (!smsAmbulanceNumber.isBlank() && smsAmbulanceNumber.equals(bystanderAiNumber)) {
                report.append("WARNING: Number A and Number B are identical; ")
                      .append("simultaneous dual calls need DISTINCT numbers. ");
            }
            report.append("Test destinations: ambulance=")
                  .append(ambulanceTestDestination.isBlank() ? "(production)" : ambulanceTestDestination)
                  .append(", bystander=")
                  .append(bystanderTestDestination.isBlank() ? "(production)" : bystanderTestDestination)
                  .append(". Webhook base: ").append(publicBaseUrl()).append(". ");
            lastValidationReport = report.toString();
            log.info("Twilio config validation: {}", lastValidationReport);
        } catch (Exception e) {
            lastValidationReport = "Validation error: " + e.getMessage();
            log.warn("Twilio config validation error: {}", e.getMessage());
        }
    }

    private void checkNumber(StringBuilder report, String label, String number, List<String> owned) {
        if (number == null || number.isBlank()) {
            report.append(label).append(": NOT CONFIGURED. ");
            return;
        }
        if (owned.contains(number.trim())) {
            report.append(label).append(": ").append(number).append(" owned by this account. ");
        } else {
            report.append(label).append(": ").append(number)
                  .append(" IS NOT owned by this account - calls/SMS will fail. ");
        }
    }
}
