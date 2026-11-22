package com.resqai;

import com.resqai.service.ProviderHttpClient;
import com.resqai.service.GeminiSttService;
import com.resqai.service.TwilioAlertService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void twilioPrefersWhatsAppChannelWhenConfigured() {
        ProviderHttpClient capture = (method, url, contentType, auth, bytes) ->
            new ProviderHttpClient.ProviderResponse(201, "{\"sid\":\"SM2\"}");
        TwilioAlertService service = new TwilioAlertService(
            capture, "AC123", "auth", "", "whatsapp:+14155238886");
        var result = service.notifyEmergencyContacts(
            "Test User", List.of("+919000000001"), "FALL", "16.67,74.25");
        assertEquals(TwilioAlertService.Status.SENT, result.status());
        assertTrue(result.detail().contains("channel=whatsapp"));
    }

    @Test
    void twilioWhatsAppSendUsesWhatsAppPrefixesOnWire() {
        String[] wireForm = {null};
        ProviderHttpClient capture = (method, url, contentType, auth, bytes) -> {
            wireForm[0] = new String(bytes, StandardCharsets.UTF_8);
            return new ProviderHttpClient.ProviderResponse(201, "{\"sid\":\"SM3\"}");
        };
        TwilioAlertService service = new TwilioAlertService(
            capture, "AC123", "auth", "", "whatsapp:+14155238886");
        service.notifyEmergencyContacts("U", List.of("+919000000001"), "FALL", null);
        assertTrue(wireForm[0].contains("To=whatsapp%3A%2B919000000001"));
        assertTrue(wireForm[0].contains("From=whatsapp%3A%2B14155238886"));
    }

    @Test
    void twilioSmsFallbackUsedWhenWhatsAppNotConfigured() {
        String[] wireForm = {null};
        ProviderHttpClient capture = (method, url, contentType, auth, bytes) -> {
            wireForm[0] = new String(bytes, StandardCharsets.UTF_8);
            return new ProviderHttpClient.ProviderResponse(201, "{\"sid\":\"SM4\"}");
        };
        TwilioAlertService service = new TwilioAlertService(
            capture, "AC123", "auth", "+15550001111", "");
        service.notifyEmergencyContacts("U", List.of("+919000000001"), "FALL", null);
        assertTrue(wireForm[0].contains("To=%2B919000000001"));
        assertTrue(wireForm[0].contains("From=%2B15550001111"));
        assertTrue(wireForm[0].startsWith("To=")); // no whatsapp: prefix on SMS path
    }

    @Test
    void twilioDetailIncludesTwilioErrorCodeOnFailure() {
        ProviderHttpClient capture = (method, url, contentType, auth, bytes) ->
            new ProviderHttpClient.ProviderResponse(400, "{\"code\":21659,\"message\":\"not owned\"}");
        TwilioAlertService service = new TwilioAlertService(
            capture, "AC123", "auth", "+15550001111", "");
        var result = service.notifyEmergencyContacts("U", List.of("+919000000001"), "FALL", null);
        assertEquals(TwilioAlertService.Status.FAILED, result.status());
        assertTrue(result.detail().contains("FAILED(400:21659)"));
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

    // ---------------- Gemini STT ----------------

    private static ProviderHttpClient geminiSttTransport(int status, String body) {
        return (m, u, c, a, b) -> new ProviderHttpClient.ProviderResponse(status, body);
    }

    private static String geminiTextOk(String text) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]}}]}";
    }

    @Test
    void geminiSttTranscribesSuccessfulResponse() {
        String[] request = {null};
        ProviderHttpClient capture = (method, url, contentType, authHeader, bytes) -> {
            request[0] = new String(bytes, StandardCharsets.UTF_8);
            return new ProviderHttpClient.ProviderResponse(
                200, geminiTextOk("person fell near the gate"));
        };
        GeminiSttService service = new GeminiSttService(capture, "test-api-key");
        var result = service.transcribe(new byte[1500], "en-IN");
        assertTrue(result.transcript().contains("person fell near the gate"));
        assertTrue(request[0].contains("inline_data"));
    }

    @Test
    void geminiSttSendsAudioInlineAndKeyAsQueryParam() {
        String[] url = {null};
        String[] auth = {null};
        String[] wire = {null};
        ProviderHttpClient capture = (method, u, contentType, authHeader, bytes) -> {
            url[0] = u;
            auth[0] = authHeader;
            wire[0] = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return new ProviderHttpClient.ProviderResponse(200, geminiTextOk("ok"));
        };
        GeminiSttService service = new GeminiSttService(capture, "test-api-key");
        service.transcribe(new byte[1500], "hi-IN");
        assertTrue(url[0].contains("key=test-api-key"));
        assertNull(auth[0]);
        assertTrue(wire[0].contains("inline_data"));
        assertTrue(wire[0].contains("VERBATIM"));
    }

    @Test
    void geminiSttRetiredModelSelfHeals() {
        java.util.List<String> urls = new java.util.ArrayList<>();
        ProviderHttpClient transport = (method, u, c, a, b) -> {
            urls.add(u);
            if (urls.size() == 1) {
                return new ProviderHttpClient.ProviderResponse(404,
                    "{\"error\":{\"message\":\"This model models/gemini-flash-lite-latest "
                        + "is no longer available to new users.\"}}");
            }
            return new ProviderHttpClient.ProviderResponse(200, geminiTextOk("second model"));
        };
        GeminiSttService service = new GeminiSttService(transport, "k");
        var result = service.transcribe(new byte[1500], null);
        assertTrue(result.transcript().contains("second model"));
        assertEquals(2, urls.size());
        assertTrue(urls.get(0).contains("gemini-flash-lite-latest"));
        assertFalse(urls.get(1).contains("gemini-flash-lite-latest"));
        // The cached resolution advanced for subsequent calls.
        service.transcribe(new byte[1500], null);
        assertEquals(3, urls.size());
        assertFalse(urls.get(2).contains("gemini-flash-lite-latest"));
    }

    @Test
    void geminiSttHonestOnProviderError() {
        GeminiSttService service = new GeminiSttService(geminiSttTransport(500, "boom"), "key");
        var result = service.transcribe(new byte[1500], null);
        assertTrue(result.transcript() == null || result.transcript().isBlank());
        assertFalse(result.detail().isBlank());
    }

    @Test
    void geminiSttHonestOnNetworkTimeout() {
        GeminiSttService service = new GeminiSttService(failing(), "key");
        var result = service.transcribe(new byte[1500], null);
        assertTrue(result.transcript() == null || result.transcript().isBlank());
        assertFalse(result.detail().isBlank());
    }

    @Test
    void geminiSttIsHonestWhenNotConfigured() {
        GeminiSttService service = new GeminiSttService(geminiSttTransport(200, "{}"), "");
        var result = service.transcribe(new byte[1500], null);
        assertTrue(result.transcript() == null || result.transcript().isBlank());
        assertTrue(result.detail().contains("not configured"));
    }

    @Test
    void geminiSttRejectsShortAudio() {
        GeminiSttService service = new GeminiSttService(geminiSttTransport(200, "{}"), "key");
        var result = service.transcribe(new byte[10], null);
        assertTrue(result.transcript() == null || result.transcript().isBlank());
        assertTrue(result.detail().contains("too short"));
    }
}
