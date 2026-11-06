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

    @Value("${resqai.twilio.account-sid-b:}")
    private String accountSidB;
    @Value("${resqai.twilio.auth-token-b:}")
    private String authTokenB;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Auto-detected owned Twilio number (cached) when voice-from env is blank. */
    private volatile String autoDetectedFrom;

    public TwilioVoiceService(ProviderHttpClient http,
                              @Value("${resqai.twilio.account-sid:}") String accountSid,
                              @Value("${resqai.twilio.auth-token:}") String authToken,
                              @Value("${resqai.twilio.voice-from:}") String voiceFrom) {
        this.http = http;
        this.accountSid = accountSid == null ? "" : accountSid.trim();
        this.authToken = authToken == null ? "" : authToken.trim();
        this.fromNumber = voiceFrom == null ? "" : voiceFrom.trim();
    }

    /**
     * The From number for API calls. An explicit env value always wins; with
     * none set, the first OWNED number on the account is auto-detected once
     * (verified caller IDs cannot originate API calls — only owned numbers).
     */
    public String resolveFromNumber() {
        if (!fromNumber.isBlank()) return fromNumber;
        String cached = autoDetectedFrom;
        if (cached != null) return cached;
        if (accountSid.isBlank() || authToken.isBlank()) return "";
        try {
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                (accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
            ProviderHttpClient.ProviderResponse response = http.send(
                "GET", String.format(
                    "https://api.twilio.com/2010-04-01/Accounts/%s/IncomingPhoneNumbers.json?PageSize=1&VoiceEnabled=true",
                    accountSid),
                "application/json", auth, new byte[0]);
            if (response.statusCode >= 200 && response.statusCode < 300) {
                JsonNode first = mapper.readTree(response.body)
                    .path("incoming_phone_numbers").path(0).path("phone_number");
                if (!first.isMissingNode() && !first.asText("").isBlank()) {
                    autoDetectedFrom = first.asText();
                    log.info("Auto-detected Twilio voice From number");
                    return autoDetectedFrom;
                }
            }
        } catch (Exception e) {
            log.warn("Twilio From-number auto-detect failed: {}", e.getMessage());
        }
        autoDetectedFrom = ""; // negative cache; not retried every request
        return "";
    }

    public boolean isConfigured() {
        return !accountSid.isBlank() && !authToken.isBlank() && !resolveFromNumber().isBlank();
    }

    /**
     * Places ONE outbound call whose audio/TwiML is served by our webhook.
     * Never redials automatically — retries are the caller's explicit decision
     * guarded by idempotency.
     */
    public CallResult placeEmergencyCall(String toNumber, String twimlUrl, String statusCallbackUrl) {
        String from = resolveFromNumber();
        if (accountSid.isBlank() || authToken.isBlank() || from.isBlank()) {
            return new CallResult(Status.NOT_CONFIGURED, null,
                "Twilio Voice credentials are not configured; automated calling is unavailable.",
                Instant.now());
        }
        return placeEmergencyCallFrom(toNumber, from, twimlUrl, statusCallbackUrl);
    }

    /** Same call with an EXPLICIT From number (TwilioConfigurationService Number A). */
    public CallResult placeEmergencyCallFrom(String toNumber, String fromNumber,
                                             String twimlUrl, String statusCallbackUrl) {
        Instant attemptedAt = Instant.now();
        String from = fromNumber == null ? "" : fromNumber.trim();
        if (accountSid.isBlank() || authToken.isBlank() || from.isBlank()) {
            return new CallResult(Status.NOT_CONFIGURED, null,
                "Twilio Voice credentials or From number are not configured.",
                attemptedAt);
        }
        if (toNumber == null || toNumber.isBlank()) {
            return new CallResult(Status.FAILED, null, "No destination number configured.", attemptedAt);
        }
        try {
            StringBuilder form = new StringBuilder()
                .append("To=").append(urlEncode(toNumber))
                .append("&From=").append(urlEncode(from))
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
     * CALL 2 (bystander AI): placed from Number B, authenticated against
     * ACCOUNT B credentials (Number B may belong to a second Twilio
     * account). Independent from the ambulance call path.
     */
    public CallResult placeBystanderAICall(String toNumber, String fromNumberB,
                                           String twimlUrl, String statusCallbackUrl) {
        Instant attemptedAt = Instant.now();
        String sidB = accountSidB == null ? "" : accountSidB.trim();
        String tokenB = authTokenB == null ? "" : authTokenB.trim();
        if (sidB.isBlank() || tokenB.isBlank()) {
            return new CallResult(Status.NOT_CONFIGURED, null,
                "Twilio Number B account credentials (ACCOUNT_SID_B/AUTH_TOKEN_B) are not configured.",
                attemptedAt);
        }
        String from = fromNumberB == null ? "" : fromNumberB.trim();
        if (from.isBlank() || !isValidDestination(from.replace("whatsapp:", ""))) {
            return new CallResult(Status.NOT_CONFIGURED, null,
                "Twilio Number B is not configured.", attemptedAt);
        }
        if (toNumber == null || toNumber.isBlank()) {
            return new CallResult(Status.FAILED, null, "No bystander number provided.", attemptedAt);
        }
        try {
            StringBuilder form = new StringBuilder()
                .append("To=").append(urlEncode(toNumber))
                .append("&From=").append(urlEncode(from))
                .append("&Url=").append(urlEncode(twimlUrl));
            if (statusCallbackUrl != null && !statusCallbackUrl.isBlank()) {
                form.append("&StatusCallback=").append(urlEncode(statusCallbackUrl));
                form.append("&StatusCallbackEvent=completed");
            }
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                (sidB + ":" + tokenB).getBytes(StandardCharsets.UTF_8));
            ProviderHttpClient.ProviderResponse response = http.send(
                "POST", String.format(CALLS_URL, sidB),
                "application/x-www-form-urlencoded", auth,
                form.toString().getBytes(StandardCharsets.UTF_8));
            if (response.statusCode >= 200 && response.statusCode < 300) {
                String sid = mapper.readTree(response.body).path("sid").asText(null);
                log.info("bystander AI call initiated from account B, sid={}", sid);
                return new CallResult(Status.INITIATED, sid, "twilio:voice:accountB", attemptedAt);
            }
            String code = extractCode(response.body);
            String message = extractMessage(response.body);
            if (response.statusCode == 401 || response.statusCode == 403) {
                return new CallResult(Status.INVALID_CREDENTIALS, null,
                    "Account B credentials rejected (HTTP " + response.statusCode + ").", attemptedAt);
            }
            return new CallResult(Status.RESTRICTED, null,
                "Twilio refused the bystander call (HTTP " + response.statusCode
                    + (code.isEmpty() ? "" : " code " + code) + ")"
                    + (message.isEmpty() ? "" : ": " + message), attemptedAt);
        } catch (Exception e) {
            log.warn("bystander AI call failed: {}", e.getMessage());
            return new CallResult(Status.FAILED, null,
                "Call request failed (network/timeout).", attemptedAt);
        }
    }

    /** Validates a bystander-webhook signature against ACCOUNT B's token. */
    public boolean isBystanderSignatureValid(String signatureHeader, String requestUrl,
                                             Map<String, String> formParams) {
        String tokenB = authTokenB == null ? "" : authTokenB.trim();
        if (tokenB.isBlank() || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        try {
            StringBuilder payload = new StringBuilder(requestUrl);
            new TreeMap<>(formParams).forEach((k, v) ->
                payload.append(k).append(v == null ? "" : v));
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(tokenB.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] digest = mac.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(digest);
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** True when account B credentials exist (bystander webhooks enforce them). */
    public boolean isBystanderAccountConfigured() {
        String sidB = accountSidB == null ? "" : accountSidB.trim();
        String tokenB = authTokenB == null ? "" : authTokenB.trim();
        return !sidB.isBlank() && !tokenB.isBlank();
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
