package com.resqai;

import com.resqai.controller.TwilioWebhookController;
import com.resqai.dto.BystanderDtos;
import com.resqai.model.BystanderReportDocument;
import com.resqai.model.EmergencyProfileDocument;
import com.resqai.model.MedicalAccessAuditDocument;
import com.resqai.model.UserDocument;
import com.resqai.repository.BystanderReportRepository;
import com.resqai.repository.EmergencyProfileRepository;
import com.resqai.repository.MedicalAccessAuditRepository;
import com.resqai.repository.UserRepository;
import com.resqai.service.EmergencyCallScriptService;
import com.resqai.service.ProviderHttpClient;
import com.resqai.service.SarvamTtsService;
import com.resqai.service.StateLanguageResolver;
import com.resqai.service.TwilioVoiceService;
import com.resqai.service.BystanderReportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Emergency-call flow: state->language resolution, script honesty, Sarvam
 * TTS wire behavior, Twilio Voice statuses, webhook signature validation,
 * call idempotency, and the minimum-necessary medical summary.
 */
class EmergencyCallFlowTest {

    // ---------------- State -> language ----------------

    @Test
    void resolvesCoreStateCapitalsToTheirPrimaryLanguage() {
        assertEquals("mr-IN", StateLanguageResolver.resolve(16.70, 74.24).languageCode()); // Kolhapur
        assertEquals("mr-IN", StateLanguageResolver.resolve(18.52, 73.86).languageCode()); // Pune
        assertEquals("mr-IN", StateLanguageResolver.resolve(19.07, 72.88).languageCode()); // Mumbai
        assertEquals("kn-IN", StateLanguageResolver.resolve(12.97, 77.59).languageCode()); // Bengaluru
        assertEquals("gu-IN", StateLanguageResolver.resolve(23.03, 72.58).languageCode()); // Ahmedabad
        assertEquals("bn-IN", StateLanguageResolver.resolve(22.57, 88.36).languageCode()); // Kolkata
        assertEquals("ta-IN", StateLanguageResolver.resolve(13.08, 80.27).languageCode()); // Chennai
        assertEquals("te-IN", StateLanguageResolver.resolve(17.38, 78.48).languageCode()); // Hyderabad
        assertEquals("ml-IN", StateLanguageResolver.resolve(9.93, 76.27).languageCode());  // Kochi
        assertEquals("pa-IN", StateLanguageResolver.resolve(30.91, 75.85).languageCode()); // Ludhiana
        assertEquals("hi-IN", StateLanguageResolver.resolve(26.85, 80.95).languageCode()); // Lucknow
    }

    @Test
    void uncertainPointsResolveToNullNeverAGuess() {
        assertNull(StateLanguageResolver.resolve(null, 74.24));
        assertNull(StateLanguageResolver.resolve(16.70, null));
        assertNull(StateLanguageResolver.resolve(0.0, 0.0));      // ocean
        assertNull(StateLanguageResolver.resolve(25.6, 85.1));    // Bihar (not covered)
        assertNull(StateLanguageResolver.resolve(14.5, 78.1));    // KA/AP mixed overlap stays uncertain
    }

    // ---------------- Call script ----------------

    private final EmergencyCallScriptService scripts = new EmergencyCallScriptService();

    @Test
    void scriptSpeaksOnlyFactsAndNamesMissingInformation() {
        var segments = scripts.buildScript(
            "ROAD_ACCIDENT", 18.5204, 73.8567,
            "Asha Patil", "O+", List.of("penicillin"), List.of("diabetes"),
            StateLanguageResolver.resolve(18.5204, 73.8567));
        assertEquals(3, segments.size()); // EN + HI + mr-IN opener
        String en = segments.get(0).text();
        assertTrue(en.contains("ResQAI's automated emergency reporting assistant"));
        assertTrue(en.contains("road accident"));
        assertTrue(en.contains("latitude 18.5204, longitude 73.8567"));
        assertTrue(en.contains("Asha Patil"));
        assertTrue(en.contains("O+"));
        assertTrue(en.contains("penicillin"));
        assertTrue(en.contains("diabetes"));
        // Location repeated before the summary, as required.
        assertTrue(en.indexOf("Repeating the incident location") < en.indexOf("Summary:"));
    }

    @Test
    void scriptNeverInventsMissingMedicalFields() {
        var segments = scripts.buildScript(
            "UNCONSCIOUS", null, null, null, null, null, null, null);
        assertEquals(2, segments.size()); // no regional segment without coordinates
        String en = segments.get(0).text();
        assertTrue(en.contains("coordinates unavailable"));
        assertTrue(en.contains("Blood group: not available"));
        assertTrue(en.contains("Known allergies: not available"));
        assertFalse(en.contains("O+"));
    }

    // ---------------- Sarvam TTS ----------------

    private static ProviderHttpClient ttsTransport(int status, String body) {
        return (m, u, c, a, b) -> new ProviderHttpClient.ProviderResponse(status, body);
    }

    @Test
    void sarvamTtsSynthesizesAndCachesPerTextAndLanguage() {
        String b64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        String[] authHeader = {null};
        String[] body = {null};
        ProviderHttpClient capture = (m, u, c, a, b) -> {
            authHeader[0] = a;
            body[0] = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            return new ProviderHttpClient.ProviderResponse(
                200, "{\"audios\":[\"" + b64 + "\"]}");
        };
        SarvamTtsService tts = new SarvamTtsService(capture, "key");
        var first = tts.synthesize("Emergency near the gate.", "mr-IN");
        assertEquals(SarvamTtsService.Status.SYNTHESIZED, first.status());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, first.audioWav());
        assertEquals("Bearer key", authHeader[0]);
        assertTrue(body[0].contains("\"target_language_code\":\"mr-IN\""));
        // Second call is served from cache — no second provider request.
        var second = tts.synthesize("Emergency near the gate.", "mr-IN");
        assertEquals("cache", second.detail());
        assertNotNull(tts.cachedAudio(
            SarvamTtsService.cacheKey("Emergency near the gate.", "mr-IN")));
    }

    @Test
    void sarvamTtsIsHonestAboutFailures() {
        assertEquals(SarvamTtsService.Status.INVALID_CREDENTIALS,
            new SarvamTtsService(ttsTransport(401, ""), "bad").synthesize("x", "en-IN").status());
        assertEquals(SarvamTtsService.Status.FAILED,
            new SarvamTtsService(ttsTransport(500, "boom"), "k").synthesize("x", "en-IN").status());
        assertEquals(SarvamTtsService.Status.NOT_CONFIGURED,
            new SarvamTtsService(ttsTransport(200, "{}"), "").synthesize("x", "en-IN").status());
    }

    // ---------------- Twilio Voice ----------------

    @Test
    void twilioVoiceReportsInitiatedOnlyOnAcceptance() {
        ProviderHttpClient ok = (m, u, c, a, b) ->
            new ProviderHttpClient.ProviderResponse(201, "{\"sid\":\"CA123\"}");
        var result = new TwilioVoiceService(ok, "AC1", "t", "+15550001111")
            .placeEmergencyCall("+919373129599", "https://x/twiml", null);
        assertEquals(TwilioVoiceService.Status.INITIATED, result.status());
        assertEquals("CA123", result.callSid());
    }

    @Test
    void twilioVoiceReportsRestrictedAndCredentialFailuresHonestly() {
        ProviderHttpClient restricted = (m, u, c, a, b) ->
            new ProviderHttpClient.ProviderResponse(400,
                "{\"code\":21272,\"message\":\"not allowed for trial\"}");
        var r1 = new TwilioVoiceService(restricted, "AC1", "t", "+15550001111")
            .placeEmergencyCall("+919373129599", "https://x/twiml", null);
        assertEquals(TwilioVoiceService.Status.RESTRICTED, r1.status());
        assertTrue(r1.detail().contains("21272"));

        var r2 = new TwilioVoiceService(ttsTransport(401, ""), "AC1", "bad", "+15550001111")
            .placeEmergencyCall("+919373129599", "https://x/twiml", null);
        assertEquals(TwilioVoiceService.Status.INVALID_CREDENTIALS, r2.status());

        var r3 = new TwilioVoiceService(ttsTransport(201, "{}"), "AC1", "t", "")
            .placeEmergencyCall("+919373129599", "https://x/twiml", null);
        assertEquals(TwilioVoiceService.Status.NOT_CONFIGURED, r3.status());
    }

    @Test
    void twilioVoiceAutoDetectsOwnedNumberWhenEnvBlank() {
        // No explicit voice-from env: the service finds the account's OWNED
        // number (verified caller IDs cannot originate API calls).
        ProviderHttpClient owned = (m, u, c, a, b) -> new ProviderHttpClient.ProviderResponse(
            200, "{\"incoming_phone_numbers\":[{\"phone_number\":\"+19789973318\"}]}");
        TwilioVoiceService svc = new TwilioVoiceService(owned, "AC1", "t", "");
        assertEquals("+19789973318", svc.resolveFromNumber());
        assertTrue(svc.isConfigured());

        // No owned numbers at all -> stays honestly NOT configured.
        ProviderHttpClient none = (m, u, c, a, b) -> new ProviderHttpClient.ProviderResponse(
            200, "{\"incoming_phone_numbers\":[]}");
        TwilioVoiceService empty = new TwilioVoiceService(none, "AC1", "t", "");
        assertEquals("", empty.resolveFromNumber());
        assertFalse(empty.isConfigured());

        // Explicit env value always wins over detection.
        TwilioVoiceService explicit = new TwilioVoiceService(none, "AC1", "t", "+18005550000");
        assertEquals("+18005550000", explicit.resolveFromNumber());
    }

    // ---------------- Webhook signature ----------------

    /** Computes the signature with the algorithm exactly as documented by Twilio. */
    private static String sign(String url, Map<String, String> params, String token) throws Exception {
        StringBuilder payload = new StringBuilder(url);
        new TreeMap<>(params).forEach((k, v) -> payload.append(k).append(v));
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(token.getBytes(), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.toString().getBytes()));
    }

    @Test
    void signatureValidationAcceptsValidAndRejectsEverythingElse() throws Exception {
        TwilioVoiceService svc = new TwilioVoiceService(ttsTransport(200, "{}"), "AC1", "token", "+1555");
        String url = "https://resqai-backend-n128.onrender.com/api/v1/twiml/incident/r1";
        Map<String, String> params = new HashMap<>();
        params.put("CallSid", "CA42");
        params.put("Called", "+919373129599");
        String good = sign(url, params, "token");
        assertTrue(svc.isSignatureValid(good, url, params));
        assertFalse(svc.isSignatureValid(good + "x", url, params));          // tampered
        assertFalse(svc.isSignatureValid(good, url + "?x=1", params));       // different URL
        assertFalse(svc.isSignatureValid(null, url, params));                // missing
        assertFalse(svc.isSignatureValid(good, url, Map.of()));              // different params
        // Token absent -> validation impossible -> endpoints closed instead.
        TwilioVoiceService noToken = new TwilioVoiceService(ttsTransport(200, "{}"), "AC1", "", "");
        assertFalse(noToken.shouldValidateSignatures());
    }

    // ---------------- TwiML webhook ----------------

    private BystanderReportDocument verifiedReport() {
        BystanderReportDocument report = new BystanderReportDocument();
        report.setReportId("r-1");
        report.setEmergencyCode("abcdefghijklmnopqrstuv");
        report.setIdentityVerification("VERIFIED");
        report.setCategory("ROAD_ACCIDENT");
        report.setLatitude(18.5204);
        report.setLongitude(73.8567);
        return report;
    }

    @Test
    void twimlEndpointServesThreeLanguageScriptToSignedTwilioOnly() throws Exception {
        BystanderReportRepository reports = mock(BystanderReportRepository.class);
        UserRepository users = mock(UserRepository.class);
        EmergencyProfileRepository profiles = mock(EmergencyProfileRepository.class);
        UserDocument victim = new UserDocument();
        victim.setFullName("Asha Patil");
        when(users.findByEmergencyCode("abcdefghijklmnopqrstuv")).thenReturn(Optional.of(victim));
        EmergencyProfileDocument profile = new EmergencyProfileDocument();
        profile.setBloodGroup("O+");
        profile.setAllergies(List.of("penicillin"));
        when(profiles.findByUserId(anyString())).thenReturn(Optional.of(profile));
        when(reports.findByReportId("r-1")).thenReturn(Optional.of(verifiedReport()));

        ProviderHttpClient ttsOk = (m, u, c, a, b) -> new ProviderHttpClient.ProviderResponse(
            200, "{\"audios\":[\"" + Base64.getEncoder().encodeToString(new byte[]{9}) + "\"]}");
        TwilioWebhookController controller = new TwilioWebhookController(
            reports, users, profiles,
            new TwilioVoiceService(ttsOk, "AC1", "token", "+1555"),
            new SarvamTtsService(ttsOk, "key"),
            scripts); // publicBaseUrl field left null -> default URL

        Map<String, String> form = new HashMap<>();
        form.put("CallSid", "CA1");
        // Unsigned request is rejected even though everything else is valid.
        assertEquals(403, controller.twiml("r-1", null, form, new HashMap<>()).getStatusCodeValue());

        String url = "https://resqai-backend-n128.onrender.com/api/v1/twiml/incident/r-1";
        String signature = sign(url, form, "token");
        var response = controller.twiml("r-1", signature, form, new HashMap<>());
        assertEquals(200, response.getStatusCodeValue());
        String xml = response.getBody();
        // All three language segments are played as synthesized audio.
        assertEquals(3, xml.split("<Play>", -1).length - 1);
        assertFalse(xml.contains("<Say")); // synthesis worked, no fallback
        assertFalse(xml.contains("Asha Patil")); // medical data only in TTS audio URL key, not in TwiML
    }

    @Test
    void twimlFallsBackToSayForEnAndHiWhenSynthesisFails() throws Exception {
        BystanderReportRepository reports = mock(BystanderReportRepository.class);
        UserRepository users = mock(UserRepository.class);
        EmergencyProfileRepository profiles = mock(EmergencyProfileRepository.class);
        when(users.findByEmergencyCode(anyString())).thenReturn(Optional.empty());
        when(reports.findByReportId("r-1")).thenReturn(Optional.of(verifiedReport()));

        ProviderHttpClient failing = (m, u, c, a, b) ->
            new ProviderHttpClient.ProviderResponse(500, "boom");
        TwilioWebhookController controller = new TwilioWebhookController(
            reports, users, profiles,
            new TwilioVoiceService(failing, "AC1", "token", "+1555"),
            new SarvamTtsService(failing, "key"),
            scripts);

        Map<String, String> form = new HashMap<>();
        String url = "https://resqai-backend-n128.onrender.com/api/v1/twiml/incident/r-1";
        var response = controller.twiml("r-1", sign(url, form, "token"), form, new HashMap<>());
        String xml = response.getBody();
        // EN + HI spoken by Twilio Say; regional segment skipped, not misread.
        assertTrue(xml.contains("<Say language=\"en-IN\""));
        assertTrue(xml.contains("<Say language=\"hi-IN\""));
        assertFalse(xml.contains("mr-IN"));
        assertTrue(xml.contains("not available")); // honest speech about missing data
    }

    // ---------------- Idempotent call + medical summary ----------------

    @Test
    void callIsInitiatedAtMostOncePerReport() {
        BystanderReportService service = new BystanderReportService(
            mock(BystanderReportRepository.class), mock(UserRepository.class),
            mock(com.resqai.repository.IncidentRepository.class),
            mock(EmergencyProfileRepository.class),
            mock(com.resqai.repository.EmergencyContactRepository.class),
            mock(com.resqai.service.TwilioAlertService.class),
            mock(TwilioVoiceService.class),
            mock(MedicalAccessAuditRepository.class),
            mock(EmergencyCallScriptService.class));
        // Inject config values (field injection via reflection as Spring would).
        org.springframework.test.util.ReflectionTestUtils.setField(
            service, "emergencyCallDestination", "+919373129599");
        org.springframework.test.util.ReflectionTestUtils.setField(
            service, "publicBaseUrl", "https://resqai-backend-n128.onrender.com");

        TwilioVoiceService voice = mock(TwilioVoiceService.class);
        when(voice.placeEmergencyCall(anyString(), anyString(), anyString()))
            .thenReturn(new TwilioVoiceService.CallResult(
                TwilioVoiceService.Status.INITIATED, "CA77", "twilio:voice",
                java.time.Instant.now()));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "voice", voice);

        BystanderReportDocument report = verifiedReport();
        service.initiateEmergencyCallOnce(report, new UserDocument());
        assertEquals("INITIATED", report.getCallStatus());
        assertEquals("CA77", report.getCallSid());

        // A retry (same report reaching the service again) must NOT call twice.
        service.initiateEmergencyCallOnce(report, new UserDocument());
        verify(voice, org.mockito.Mockito.times(1)).placeEmergencyCall(anyString(), anyString(), anyString());
    }

    @Test
    void invalidDestinationIsReportedNotSilentlyReplaced() {
        BystanderReportService service = new BystanderReportService(
            mock(BystanderReportRepository.class), mock(UserRepository.class),
            mock(com.resqai.repository.IncidentRepository.class),
            mock(EmergencyProfileRepository.class),
            mock(com.resqai.repository.EmergencyContactRepository.class),
            mock(com.resqai.service.TwilioAlertService.class),
            mock(TwilioVoiceService.class),
            mock(MedicalAccessAuditRepository.class),
            mock(EmergencyCallScriptService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(
            service, "emergencyCallDestination", "not-a-number");
        org.springframework.test.util.ReflectionTestUtils.setField(
            service, "publicBaseUrl", "https://x");

        TwilioVoiceService voice = mock(TwilioVoiceService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "voice", voice);
        BystanderReportDocument report = verifiedReport();
        service.initiateEmergencyCallOnce(report, new UserDocument());
        assertEquals("FAILED", report.getCallStatus());
        assertTrue(report.getCallDetail().contains("not configured"));
        verify(voice, never()).placeEmergencyCall(anyString(), anyString(), anyString());
    }

    @Test
    void medicalSummaryCarriesOnlyExistingFieldsAndIsAudited() {
        BystanderReportRepository reports = mock(BystanderReportRepository.class);
        EmergencyProfileRepository profiles = mock(EmergencyProfileRepository.class);
        MedicalAccessAuditRepository audit = mock(MedicalAccessAuditRepository.class);
        UserRepository users = mock(UserRepository.class);
        EmergencyProfileDocument profile = new EmergencyProfileDocument();
        profile.setBloodGroup("  ");          // blank -> null, never trimmed into a fact
        profile.setAllergies(List.of("penicillin", " "));
        profile.setMedicalConditions(new java.util.ArrayList<>());
        when(profiles.findByUserId("u1")).thenReturn(Optional.of(profile));
        UserDocument victim = new UserDocument();
        victim.setId("u1");
        victim.setFullName("Asha Patil");
        when(users.findByEmergencyCode(anyString())).thenReturn(Optional.of(victim));

        BystanderReportService service = new BystanderReportService(
            reports, users, mock(com.resqai.repository.IncidentRepository.class),
            profiles, mock(com.resqai.repository.EmergencyContactRepository.class),
            mock(com.resqai.service.TwilioAlertService.class), mock(TwilioVoiceService.class),
            audit, mock(EmergencyCallScriptService.class));

        BystanderDtos.VictimSummary summary =
            service.buildAuthorizedSummary(verifiedReport(), victim);
        assertEquals("Asha Patil", summary.emergencyName);
        assertNull(summary.bloodGroup);                     // blank stays unavailable
        assertEquals(List.of("penicillin"), summary.allergies);
        assertNull(summary.medicalConditions);              // empty stays unavailable
        assertNull(summary.currentMedications);

        ArgumentCaptor<MedicalAccessAuditDocument> captor =
            ArgumentCaptor.forClass(MedicalAccessAuditDocument.class);
        verify(audit).save(captor.capture());
        assertEquals("BYSTANDER_REPORT_DISCLOSURE", captor.getValue().getAction());
        assertEquals("r-1", captor.getValue().getReportId());
    }
}
