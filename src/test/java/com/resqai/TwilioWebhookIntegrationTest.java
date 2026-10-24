package com.resqai;

import com.resqai.model.BystanderReportDocument;
import com.resqai.model.UserDocument;
import com.resqai.repository.BystanderReportRepository;
import com.resqai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for the real Twilio request shape: form-encoded POST with
 * X-Twilio-Signature. A previous version used @RequestBody Map, which Spring
 * rejected with 415 BEFORE the controller ran — Twilio then played "An
 * application error has occurred. Goodbye." This test goes through Spring's
 * full converter pipeline so that class of bug cannot hide in unit tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "resqai.twilio.account-sid=AC-test",
    "resqai.twilio.auth-token=token",
    "resqai.sarvam.api-key=",          // TTS unconfigured -> <Say> fallback
    "resqai.emergency-call.destination=+917676941547",
    "resqai.public-base-url=https://resqai-backend-n128.onrender.com"
})
class TwilioWebhookIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired BystanderReportRepository reports;
    @Autowired UserRepository users;

    private static String sign(String url, Map<String, String> params, String token)
            throws Exception {
        StringBuilder payload = new StringBuilder(url);
        new TreeMap<>(params).forEach((k, v) -> payload.append(k).append(v));
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(token.getBytes(), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.toString().getBytes()));
    }

    @Test
    void formEncodedSignedTwilioRequestReturnsSpokenTwiML() throws Exception {
        String code = "webhooktest" + UUID.randomUUID().toString().substring(0, 8);
        UserDocument victim = new UserDocument();
        victim.setId(UUID.randomUUID().toString());
        victim.setFullName("Webhook Test");
        victim.setEmail("webhook-" + UUID.randomUUID() + "@example.com");
        victim.setNormalizedEmail(victim.getEmail());
        victim.setEmergencyCode(code);
        users.save(victim);

        BystanderReportDocument report = new BystanderReportDocument();
        report.setReportId(UUID.randomUUID().toString());
        report.setEmergencyCode(code);
        report.setVictimEmergencyId("RQ-TEST");
        report.setIdentityVerification("VERIFIED");
        report.setCategory("ROAD_ACCIDENT");
        report.setLatitude(16.70);
        report.setLongitude(74.24);
        reports.save(report);

        String url = "https://resqai-backend-n128.onrender.com/api/v1/twiml/incident/"
            + report.getReportId();
        Map<String, String> form = Map.of("CallSid", "CA-TEST-1", "Called", "+917676941547");

        // FORM-ENCODED body exactly as Twilio sends it — the 415 regression.
        String body = mockMvc.perform(
                post("/api/v1/twiml/incident/" + report.getReportId())
                    .contentType("application/x-www-form-urlencoded")
                    .param("CallSid", "CA-TEST-1")
                    .param("Called", "+917676941547")
                    .header("X-Twilio-Signature", sign(url, form, "token")))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/xml"))
            .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("<Say language=\"en-IN\""));
        assertTrue(body.contains("<Say language=\"hi-IN\""));
        assertTrue(body.contains("road accident"));
        assertTrue(body.contains("Webhook Test"));
        assertTrue(body.contains("16.700, 74.240"));

        // Unsigned request is rejected — medical speech never leaks.
        mockMvc.perform(post("/api/v1/twiml/incident/" + report.getReportId())
                .contentType("application/x-www-form-urlencoded")
                .param("CallSid", "CA-TEST-1"))
            .andExpect(status().isForbidden());

        reports.delete(report);
        users.delete(victim);
    }

    @Test
    void ttsAudioEndpointAcceptsTwiliosDefaultGetMethod() throws Exception {
        // Twilio <Play> fetches audio via GET. A POST-only mapping 405'd and
        // made real calls play the "application error" message. GET with a
        // valid signature must reach the handler (404 for unknown audio —
        // never 405); GET without a signature is rejected.
        String path = "/api/v1/tts/incident/some-report/some-key";
        String url = "https://resqai-backend-n128.onrender.com" + path;
        String signature = sign(url, Map.of(), "token");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(path).header("X-Twilio-Signature", signature))
            .andExpect(status().isNotFound()); // reaches handler; audio unknown

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(path))
            .andExpect(status().isForbidden());
    }
}
