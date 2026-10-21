package com.resqai;

import com.resqai.service.ProviderHttpClient;
import com.resqai.service.SarvamSpeechService;
import com.resqai.service.TwilioAlertService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock-transport tests for the provider integrations: success, provider
 * rejection, network failure, and missing credentials. These verify the
 * HONEST STATUS contract — no result ever fakes success.
 */
class ProviderServicesTest {

    private static ProviderHttpClient fake(int statusCode, String body) {
        return (method, url, contentType, auth, bytes) ->
            new ProviderHttpClient.ProviderResponse(statusCode, body);
    }

    private static ProviderHttpClient failing() {
        return (method, url, contentType, auth, bytes) -> {
            throw new java.io.IOException("connection timed out");
        };
    }

    // ---------------- Twilio ----------------

    @Test
    void twilioSendsSmsPerContactAndReportsSent() {
        ProviderHttpClient.ProviderResponse[] seen = {null};
        ProviderHttpClient capture = (method, url, contentType, auth, bytes) -> {
            seen[0] = new ProviderHttpClient.ProviderResponse(201, "{\"sid\":\"SM1\"}");
            return seen[0];
        };
        TwilioAlertService service = new TwilioAlertService(capture, "AC123", "auth", "+15550001111");
        var result = service.notifyEmergencyContacts(
            "Test User", List.of("+919000000001", "+919000000002"), "FALL", "16.67,74.25");
        assertEquals(TwilioAlertService.Status.SENT, result.status());
        assertEquals(2, result.recipientsAttempted().size());
        assertTrue(seen[0].body.contains("SM1"));
    }

    @Test
    void twilioReportsInvalidCredentialsOn401() {
        TwilioAlertService service = new TwilioAlertService(fake(401, ""), "AC123", "bad", "+15550001111");
        var result = service.notifyEmergencyContacts("U", List.of("+919000000001"), "FALL", null);
        assertEquals(TwilioAlertService.Status.INVALID_CREDENTIALS, result.status());
    }

    @Test
    void twilioReportsFailureWhenProviderUnreachable() {
        TwilioAlertService service = new TwilioAlertService(failing(), "AC123", "auth", "+15550001111");
        var result = service.notifyEmergencyContacts("U", List.of("+919000000001"), "FALL", null);
        assertEquals(TwilioAlertService.Status.FAILED, result.status());
    }

    @Test
    void twilioIsHonestWhenNotConfigured() {
        TwilioAlertService service = new TwilioAlertService(fake(500, ""), "", "", "");
        var result = service.notifyEmergencyContacts("U", List.of("+919000000001"), "FALL", null);
        assertEquals(TwilioAlertService.Status.NOT_CONFIGURED, result.status());
        assertTrue(result.detail().contains("not configured"));
    }

    @Test
    void twilioFailsHonestyWhenNoContactsExist() {
        TwilioAlertService service = new TwilioAlertService(fake(200, "{}"), "AC123", "auth", "+15550001111");
        var result = service.notifyEmergencyContacts("U", List.of(), "FALL", null);
        assertEquals(TwilioAlertService.Status.FAILED, result.status());
    }

    // ---------------- Sarvam ----------------

    @Test
    void sarvamTranscribesSuccessfulResponse() {
        ProviderHttpClient.ProviderResponse[] seen = {null};
        ProviderHttpClient capture = (method, url, contentType, authHeader, bytes) -> {
            seen[0] = new ProviderHttpClient.ProviderResponse(
                200, "{\"transcript\":\"person fell near the gate\"}");
            return seen[0];
        };
        SarvamSpeechService service = new SarvamSpeechService(capture, "test-api-key");
        var result = service.transcribe(
            "fake-audio".getBytes(StandardCharsets.UTF_8), "en-IN");
        assertEquals(SarvamSpeechService.Status.TRANSCRIBED, result.status());
        assertEquals("person fell near the gate", result.transcript());
        assertTrue(seen[0].body.contains("near the gate"));
    }

    @Test
    void sarvamReportsInvalidCredentialsOn401() {
        SarvamSpeechService service = new SarvamSpeechService(fake(401, ""), "bad-key");
        var result = service.transcribe("audio".getBytes(StandardCharsets.UTF_8), null);
        assertEquals(SarvamSpeechService.Status.INVALID_CREDENTIALS, result.status());
        assertNull(result.transcript());
    }

    @Test
    void sarvamReportsFailureOnProviderError() {
        SarvamSpeechService service = new SarvamSpeechService(fake(500, "boom"), "key");
        var result = service.transcribe("audio".getBytes(StandardCharsets.UTF_8), null);
        assertEquals(SarvamSpeechService.Status.FAILED, result.status());
    }

    @Test
    void sarvamReportsFailureWhenNetworkTimesOut() {
        SarvamSpeechService service = new SarvamSpeechService(failing(), "key");
        var result = service.transcribe("audio".getBytes(StandardCharsets.UTF_8), null);
        assertEquals(SarvamSpeechService.Status.FAILED, result.status());
        assertTrue(result.detail().contains("network/timeout"));
    }

    @Test
    void sarvamIsHonestWhenNotConfigured() {
        SarvamSpeechService service = new SarvamSpeechService(fake(200, "{}"), "");
        var result = service.transcribe("audio".getBytes(StandardCharsets.UTF_8), null);
        assertEquals(SarvamSpeechService.Status.NOT_CONFIGURED, result.status());
        assertNull(result.transcript());
    }

    @Test
    void sarvamRejectsEmptyAudio() {
        SarvamSpeechService service = new SarvamSpeechService(fake(200, "{}"), "key");
        var result = service.transcribe(new byte[0], null);
        assertEquals(SarvamSpeechService.Status.FAILED, result.status());
    }
}
