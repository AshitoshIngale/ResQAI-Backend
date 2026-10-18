package com.resqai;

import com.resqai.model.PendingVerification;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5: assessment metadata sync tests. Idempotency, ownership, auth.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssessmentIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private VerificationService verificationService;

    private static int counter = 0;

    @BeforeEach
    void clean() {
        for (String collection : new String[]{
            "users", "emergency_profiles", "emergency_contacts",
            "emergency_incidents", "pending_verifications", "emergency_assessments"}) {
            mongoTemplate.getCollection(collection).drop();
        }
    }

    @AfterEach
    void cleanAfter() { clean(); }

    private String victimToken() throws Exception {
        String email = "as" + (++counter) + "-" + UUID.randomUUID().toString().substring(0, 6) + "@test.dev";
        String phone = "+9199" + String.format("%08d", 30000000 + counter * 173);
        String ip = "10.2." + (counter % 200) + "." + (counter % 250);
        mockMvc.perform(post("/api/v1/auth/register")
                .with(req -> { req.addHeader("X-Forwarded-For", ip); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Assess User","email":"%s","phone":"%s",
                     "password":"S3cure!Passw0rd","acceptedTermsAt":1}
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
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString())
            .get("accessToken").asText();
    }

    @Test
    void assessmentSyncIsIdempotentForTheOwner() throws Exception {
        String token = victimToken();
        String assessmentId = UUID.randomUUID().toString();
        String body = """
            {"assessmentId":"%s","urgency":"EMERGENCY","triggeredRuleId":"R_NOT_BREATHING",
             "reportedSymptoms":"IS_BREATHING_NORMALLY=NO","language":"en","clientCreatedAt":1700000000000}
            """.formatted(assessmentId);

        mockMvc.perform(post("/api/v1/assessments").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/api/v1/assessments").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(true));

        assertThatCollectionHas(1);
    }

    @Test
    void assessmentRequiresAuthenticationAndValidation() throws Exception {
        mockMvc.perform(post("/api/v1/assessments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assessmentId\":\"x\",\"urgency\":\"EMERGENCY\",\"triggeredRuleId\":\"R\",\"reportedSymptoms\":\"y\",\"language\":\"en\",\"clientCreatedAt\":1}"))
            .andExpect(status().isUnauthorized());

        String token = victimToken();
        mockMvc.perform(post("/api/v1/assessments").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assessmentId\":\"bad\",\"urgency\":\"EMERGENCY\",\"triggeredRuleId\":\"R\",\"reportedSymptoms\":\"y\",\"language\":\"en\",\"clientCreatedAt\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.assessmentId").exists());
    }

    @Test
    void assessmentOfOneUserIsDistinctFromAnother() throws Exception {
        String tokenA = victimToken();
        String tokenB = victimToken();
        String assessmentId = UUID.randomUUID().toString();
        String body = """
            {"assessmentId":"%s","urgency":"MONITOR","triggeredRuleId":"R_NO_WARNING_SIGNS",
             "reportedSymptoms":"IS_CONSCIOUS=YES","language":"en","clientCreatedAt":1}
            """.formatted(assessmentId);

        mockMvc.perform(post("/api/v1/assessments").header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        // User B uploading the SAME assessmentId creates a SEPARATE record for B
        mockMvc.perform(post("/api/v1/assessments").header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.duplicate").value(false));

        assertThatCollectionHas(2);
    }

    private void assertThatCollectionHas(long count) {
        assertThatCollectionHas(count, "emergency_assessments");
    }

    private void assertThatCollectionHas(long count, String collection) {
        org.assertj.core.api.Assertions.assertThat(
            mongoTemplate.getCollection(collection).countDocuments()).isEqualTo(count);
    }
}
