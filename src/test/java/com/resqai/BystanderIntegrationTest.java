package com.resqai;

import com.resqai.model.PendingVerification;
import com.resqai.repository.UserRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resqai.service.VerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 integration tests against the real local MongoDB (resqai_test).
 * Covers: emergency-code resolution (no private data, no enumeration),
 * bystander report creation + idempotency + validation, incident
 * association on sufficient evidence, unmatched retention, and auth
 * regression (protected endpoints still reject anonymous access).
 */
@SpringBootTest
@AutoConfigureMockMvc
class BystanderIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private VerificationService verificationService;
    @Autowired private com.resqai.repository.UserRepository userRepository;

    private static int counter = 0;

    @BeforeEach
    void clean() {
        for (String collection : new String[]{
            "users", "emergency_profiles", "emergency_contacts",
            "emergency_incidents", "bystander_reports", "pending_verifications"}) {
            mongoTemplate.getCollection(collection).drop();
        }
    }

    @AfterEach
    void cleanAfter() { clean(); }

    private String uniqueEmail() {
        return "byst" + (++counter) + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.dev";
    }

    private String uniquePhone() {
        return "+9198" + String.format("%08d", 20000000 + (++counter) * 149);
    }

    /** Registers, verifies, logs in — returns {token, emergencyCode}. */
    private record Victim(String token, String emergencyCode, String emergencyId, String userId) {}

    private Victim registerVictim() throws Exception {
        String email = uniqueEmail(), phone = uniquePhone();
        String ip = "192.168." + (counter % 200) + "." + (counter % 250);
        mockMvc.perform(post("/api/v1/auth/register")
                .with(req -> { req.addHeader("X-Forwarded-For", ip); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Victim User","email":"%s","phone":"%s",
                     "password":"S3cure!Passw0rd","bloodGroup":"B+","acceptedTermsAt":1}
                    """.formatted(email, phone)))
            .andExpect(status().isCreated());

        verificationService.issue(
            com.resqai.model.PendingVerification.Kind.EMAIL_VERIFICATION, email, "111111", 30, 10);
        mockMvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"token\":\"111111\"}".formatted(email)))
            .andExpect(status().isOk());
        verificationService.issue(
            com.resqai.model.PendingVerification.Kind.PHONE_OTP, phone, "222222", 30, 10);
        mockMvc.perform(post("/api/v1/auth/verify-phone-otp").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"%s\",\"otp\":\"222222\"}".formatted(phone)))
            .andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                .with(req -> { req.addHeader("X-Forwarded-For", ip); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"%s\",\"password\":\"S3cure!Passw0rd\"}".formatted(email)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        return new Victim(
            body.get("accessToken").asText(),
            body.get("user").get("emergencyCode").asText(),
            body.get("user").get("emergencyId").asText(),
            body.get("user").get("userId").asText());
    }

    // ---------------- Emergency-code resolution ----------------

    @Test
    void summaryResolvesCodeButDisclosesNoPrivateData() throws Exception {
        Victim victim = registerVictim();

        // Full versioned QR payload form
        mockMvc.perform(get("/api/v1/emergency-ids/RESQAI:1:" + victim.emergencyCode() + "/summary")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.9.1"); return req; }))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emergencyId").value(victim.emergencyId()))
            .andExpect(jsonPath("$.accountVerified").value(true))
            .andExpect(jsonPath("$.medicalInfoPolicy").value("PENDING"));

        // Bare code form also works
        mockMvc.perform(get("/api/v1/emergency-ids/" + victim.emergencyCode() + "/summary")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.9.2"); return req; }))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emergencyId").value(victim.emergencyId()));

        // The response must not contain medical/profile details
        MvcResult result = mockMvc.perform(get("/api/v1/emergency-ids/" + victim.emergencyCode() + "/summary")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.9.3"); return req; }))
            .andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("bloodGroup", "allergies", "phone", "email", "fullName");
    }

    @Test
    void unknownOrInvalidCodesReturnGeneric404WithoutEnumeration() throws Exception {
        mockMvc.perform(get("/api/v1/emergency-ids/doesnotexist12345678/summary")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.8.1"); return req; }))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Emergency ID not recognized"));

        // Unknown QR version is rejected
        mockMvc.perform(get("/api/v1/emergency-ids/RESQAI:2:abcdefghij1234567890/summary")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.8.2"); return req; }))
            .andExpect(status().isNotFound());

        // Display ID (RQ-...) must NOT be accepted for lookup
        Victim victim = registerVictim();
        mockMvc.perform(get("/api/v1/emergency-ids/" + victim.emergencyId() + "/summary")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.8.3"); return req; }))
            .andExpect(status().isNotFound());
    }

    // ---------------- Report creation ----------------

    private String reportBody(String reportId, String code, String category, Double lat, Double lon) {
        return """
            {"reportId":"%s","emergencyCode":"%s","category":"%s",
             "description":"Collapsed at the bus stop","identityVerified":true,
             "latitude":%s,"longitude":%s,"locationAccuracyMeters":12.0,
             "locationCapturedAt":1700000000000,"clientCreatedAt":1700000000000,
             "source":"Bystander QR scan"}
            """.formatted(reportId, code, category, lat, lon);
    }

    @Test
    void validReportIsCreatedAndRetryIsIdempotent() throws Exception {
        Victim victim = registerVictim();
        String reportId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/bystander/reports")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.7.1"); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(reportBody(reportId, "RESQAI:1:" + victim.emergencyCode(), "MEDICAL", 16.7, 74.2)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.reportId").value(reportId))
            .andExpect(jsonPath("$.victimEmergencyId").value(victim.emergencyId()))
            .andExpect(jsonPath("$.identityVerification").value("VERIFIED"))
            .andExpect(jsonPath("$.status").value("RECEIVED"))
            .andExpect(jsonPath("$.duplicate").value(false));

        // Retry with the SAME reportId: no duplicate record, duplicate=true
        mockMvc.perform(post("/api/v1/bystander/reports")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.7.1"); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(reportBody(reportId, "RESQAI:1:" + victim.emergencyCode(), "MEDICAL", 16.7, 74.2)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(true));

        assertThat(mongoTemplate.getCollection("bystander_reports").countDocuments()).isEqualTo(1);
    }

    @Test
    void unknownVictimCodeIsAcceptedAsUnverifiedReport() throws Exception {
        // Honest offline case: code that doesn't resolve — report still stored, marked UNVERIFIED
        String reportId = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/bystander/reports")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.7.2"); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(reportBody(reportId, "unknowncode1234567890ab", "ACCIDENT", 16.7, 74.2)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.identityVerification").value("UNVERIFIED"))
            .andExpect(jsonPath("$.victimEmergencyId").doesNotExist());
    }

    @Test
    void invalidReportPayloadsAreRejectedWithFieldErrors() throws Exception {
        Victim victim = registerVictim();
        // Missing UUID + bad coordinates + oversized category
        mockMvc.perform(post("/api/v1/bystander/reports")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.7.3"); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reportId":"not-a-uuid","emergencyCode":"","category":"",
                     "latitude":200.0,"longitude":999.0,"clientCreatedAt":null}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.reportId").exists())
            .andExpect(jsonPath("$.fieldErrors.emergencyCode").exists())
            .andExpect(jsonPath("$.fieldErrors.clientCreatedAt").exists());

        // Structurally invalid code payload (bad characters)
        mockMvc.perform(post("/api/v1/bystander/reports")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.7.4"); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(reportBody(UUID.randomUUID().toString(), "!!!invalid-code!!!", "MEDICAL", 16.7, 74.2)))
            .andExpect(status().isBadRequest());
    }

    // ---------------- Association with incidents ----------------

    @Test
    void reportLinksToVictimsMatchingIncidentAndOtherReportsStayUnmatched() throws Exception {
        Victim victim = registerVictim();
        // Victim's own incident, same category, same time, victim identity attached
        mockMvc.perform(post("/api/v1/incidents")
                .header("Authorization", "Bearer " + victim.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"incidentId":"%s","category":"MEDICAL","clientCreatedAt":1700000000000,
                     "victimEmergencyId":"%s","latitude":16.70001,"longitude":74.20001}
                    """.formatted(UUID.randomUUID(), victim.emergencyId())))
            .andExpect(status().isCreated());

        // Report 1: same victim code + same category + within time window -> linked
        String linked = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/bystander/reports")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.6.1"); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(reportBody(linked, victim.emergencyCode(), "MEDICAL", 16.70005, 74.20005)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.matchStatus").value("MATCHED"))
            .andExpect(jsonPath("$.linkedIncidentId").exists())
            .andExpect(jsonPath("$.matchSignals").value("VICTIM_ID"));

        // Report 2 from another bystander: unknown victim -> kept UNMATCHED, never discarded
        String unmatched = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/bystander/reports")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.6.2"); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(reportBody(unmatched, "otherusercode12345678", "MEDICAL", 16.7, 74.2)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.matchStatus").value("UNMATCHED"))
            .andExpect(jsonPath("$.linkedIncidentId").doesNotExist());

        // Both reports preserved
        assertThat(mongoTemplate.getCollection("bystander_reports").countDocuments()).isEqualTo(2);
    }

    @Test
    void distantOrLateReportsDoNotAutoLink() throws Exception {
        Victim victim = registerVictim();
        mockMvc.perform(post("/api/v1/incidents")
                .header("Authorization", "Bearer " + victim.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"incidentId":"%s","category":"MEDICAL","clientCreatedAt":1600000000000,
                     "victimEmergencyId":"%s"}
                    """.formatted(UUID.randomUUID(), victim.emergencyId())))
            .andExpect(status().isCreated());

        // 100km away and 4 hours later -> no link despite matching victim
        String reportId = UUID.randomUUID().toString();
        String lateBody = """
            {"reportId":"%s","emergencyCode":"%s","category":"MEDICAL",
             "clientCreatedAt":1714400000000,"latitude":17.7,"longitude":75.2}
            """.formatted(reportId, victim.emergencyCode());
        mockMvc.perform(post("/api/v1/bystander/reports")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.6.3"); return req; })
                .contentType(MediaType.APPLICATION_JSON).content(lateBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.matchStatus").value("UNMATCHED"));
    }

    // ---------------- Auth regression ----------------

    @Test
    void bystanderEndpointsArePublicButProtectedEndpointsStillRejectAnonymous() throws Exception {
        // A report goes through without any token (that is the bystander workflow)
        String reportId = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/bystander/reports")
                .with(req -> { req.addHeader("X-Forwarded-For", "9.9.5.1"); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(reportBody(reportId, "anycode1234567890abcd", "FIRE", null, null)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.latitude").doesNotExist());

        // Private endpoints still require auth
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/incidents/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void lookupRateLimitKicksIn() throws Exception {
        Victim victim = registerVictim();
        int tooMany = 35; // policy: 30 per 15 min per IP
        int forbidden = 0;
        for (int i = 0; i < tooMany; i++) {
            var result = mockMvc.perform(get("/api/v1/emergency-ids/" + victim.emergencyCode() + "/summary")
                    .with(req -> { req.addHeader("X-Forwarded-For", "9.9.4.9"); return req; }))
                .andReturn();
            if (result.getResponse().getStatus() == 429) forbidden++;
        }
        assertThat(forbidden).isGreaterThan(0);
    }
}
