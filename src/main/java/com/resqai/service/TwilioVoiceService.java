package com.resqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Twilio Voice for the automated emergency call placed when a VERIFIED
 * bystander report arrives. Credentials live ONLY in backend env vars.
 *
 * Honest statuses — a call is only INITIATED when Twilio accepts the request:
 *  NOT_CONFIGURED     missing credentials
 *  INITIATED          Twilio accepted; call SID recorded (NOT a dispatch claim)
 *  RESTRICTED         provider refused for account/number reasons
 *                     (e.g. trial account with no owned From number)
 *  INVALID_CREDENTIALS  401/403 from Twilio
 *  FAILED             network/timeout/other provider error
 */
@Service
public class TwilioVoiceService {

    public enum Status { NOT_CONFIGURED, INITIATED, RESTRICTED, INVALID_CREDENTIALS, FAILED }

    public record CallResult(Status status, String callSid, String detail, Instant attemptedAt) {}

    private static final Logger log = LoggerFactory.getLogger(TwilioVoiceService.class);
    private static final String CALLS_URL = "https://api.twilio.com/2010-04-01/Accounts/%s/Calls.json";

    private final ProviderHttpClient http;
    private final String accountSid;
    private final String authToken;
    private final String fromNumber;
    private final ObjectMapper mapper = new ObjectMapper();

    public TwilioVoiceService(ProviderHttpClient http,
                              @Value("${resqai.twilio.account-sid:}") String accountSid,
                              @Value("${resqai.twilio.auth-token:}") String authToken,
                              @Value("${resqai.twilio.voice-from:}") String voiceFrom) {
        this.http = http;
        this.accountSid = accountSid == null ? "" : accountSid.trim();
        this.authToken = authToken == null ? "" : authToken.trim();
        this.fromNumber = voiceFrom == null ? "" : voiceFrom.trim();
    }

    public boolean isConfigured() {
        return !accountSid.isBlank() && !authToken.isBlank() && !fromNumber.isBlank();
    }

    /**
     * Places ONE outbound call whose audio/TwiML is served by our webhook.
     * Never redials automatically — retries are the caller's explicit decision
     * guarded by idempotency.
     */
    public CallResult placeEmergencyCall(String toNumber, String twimlUrl, String statusCallbackUrl) {
        Instant attemptedAt = Instant.now();
        if (!isConfigured()) {
            return new CallResult(Status.NOT_CONFIGURED, null,
                "Twilio Voice credentials are not configured; automated calling is unavailable.",
                attemptedAt);
        }
        if (toNumber == null || toNumber.isBlank()) {
            return new CallResult(Status.FAILED, null, "No destination number configured.", attemptedAt);
        }
        try {
            StringBuilder form = new StringBuilder()
                .append("To=").append(urlEncode(toNumber))
                .append("&From=").append(urlEncode(fromNumber))
                .append("&Url=").append(urlEncode(twimlUrl));
            if (statusCallbackUrl != null && !statusCallbackUrl.isBlank()) {
                form.append("&StatusCallback=").append(urlEncode(statusCallbackUrl));
                form.append("&StatusCallbackEvent=completed");
            }
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                (accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
            ProviderHttpClient.ProviderResponse response = http.send(
                "POST", String.format(CALLS_URL, accountSid),
                "application/x-www-form-urlencoded", auth,
                form.toString().getBytes(StandardCharsets.UTF_8));

            if (response.statusCode >= 200 && response.statusCode < 300) {
                String sid = mapper.readTree(response.body).path("sid").asText(null);
                log.info("Twilio emergency call initiated, sid={}", sid);
                return new CallResult(Status.INITIATED, sid, "twilio:voice", attemptedAt);
            }
            String code = extractCode(response.body);
            String message = extractMessage(response.body);
            if (response.statusCode == 401 || response.statusCode == 403) {
                return new CallResult(Status.INVALID_CREDENTIALS, null,
                    "Twilio rejected the credentials (HTTP " + response.statusCode + ").", attemptedAt);
            }
            // 21272/21659-class refusals: account/number not permitted for this call.
            return new CallResult(Status.RESTRICTED, null,
                "Twilio refused the call (HTTP " + response.statusCode
                    + (code.isEmpty() ? "" : " code " + code) + ")"
                    + (message.isEmpty() ? "" : ": " + message), attemptedAt);
        } catch (Exception e) {
            log.warn("Twilio voice call failed: {}", e.getMessage());
            return new CallResult(Status.FAILED, null,
                "Call request failed (network/timeout).", attemptedAt);
        }
    }

    /**
     * Validates X-Twilio-Signature (HMAC-SHA1 over URL + sorted params with
     * the auth token). Webhook endpoints reject unsigned/invalid requests
     * whenever credentials are configured.
     */
    public boolean isSignatureValid(String signatureHeader, String requestUrl,
                                    Map<String, String> formParams) {
        if (authToken.isBlank()) return false;
        if (signatureHeader == null || signatureHeader.isBlank()) return false;
        try {
            StringBuilder payload = new StringBuilder(requestUrl);
            new TreeMap<>(formParams).forEach((k, v) ->
                payload.append(k).append(v == null ? "" : v));
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] digest = mac.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(digest);
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** True when signature validation SHOULD be enforced (token present). */
    public boolean shouldValidateSignatures() {
        return !authToken.isBlank();
    }

    private static String extractCode(String body) {
        return extractJsonString(body, "code");
    }

    private static String extractMessage(String body) {
        return extractJsonString(body, "message");
    }

    private static String extractJsonString(String body, String field) {
        try {
            JsonNode node = new ObjectMapper().readTree(body == null ? "" : body);
            return node.has(field) ? node.get(field).asText("") : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Valid To numbers for the call (E.164-ish); used before provider call. */
    public static boolean isValidDestination(String number) {
        return number != null && number.matches("^\\+?[0-9]{8,15}$");
    }

    /** Helper for webhook param collection (Spring gives us a multi-map). */
    public static Map<String, String> flatten(Map<String, List<String>> multi) {
        Map<String, String> out = new java.util.HashMap<>();
        multi.forEach((k, v) -> out.put(k, v == null || v.isEmpty() ? "" : v.get(0)));
        return out;
    }
}
