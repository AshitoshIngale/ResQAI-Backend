package com.resqai;

import com.resqai.model.PendingVerification;
import com.resqai.service.VerificationService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 integration tests against the REAL local MongoDB (resqai_test
 * database — isolated from development data). The Spring context booting at
 * all proves the configured MongoDB connection works: the startup migration
 * runner reads collections during bootstrap.
 *
 * Covers: profile read/update for the authenticated owner, cross-user
 * isolation, contacts CRUD, incident creation with idempotency, batch sync,
 * validation errors, and authentication rejection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.Random.class)
class ResQaiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private com.resqai.service.VerificationService verificationService;

    private static int counter = 0;

    @BeforeEach
    void cleanCollections() {
        // Isolated test database only; development data lives in "resqai".
        for (String collection : new String[]{
            "users", "emergency_profiles", "emergency_contacts",
            "emergency_incidents", "pending_verifications"}) {
            mongoTemplate.getCollection(collection).drop();
        }
    }

    @AfterEach
    void cleanAfter() {
        cleanCollections();
    }

    private String uniqueEmail() {
        return "it" + (++counter) + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.dev";
    }

    private String uniquePhone() {
        return "+9198" + String.format("%08d", 10000000 + (++counter) * 137);
    }

    private String registerBody(String email, String phone) {
        return """
            {"fullName":"Integration Tester","email":"%s","phone":"%s",
             "password":"S3cure!Passw0rd","bloodGroup":"O+","acceptedTermsAt":1}
            """.formatted(email, phone);
    }

    /** Full happy path through the real API: register -> verify both contacts -> login. */
    private String registerVerifyLogin(String email, String phone) throws Exception {
        String clientIp = "10." + (counter) + "." + (counter % 250) + ".9";
        mockMvc.perform(post("/api/v1/auth/register")
                .with(req -> { req.addHeader("X-Forwarded-For", clientIp); return req; })
                .contentType(MediaType.APPLICATION_JSON).content(registerBody(email, phone)))
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

        // Unique forwarded IP per test user keeps the login rate limiter per-client
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                .with(req -> { req.addHeader("X-Forwarded-For", clientIp); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"%s\",\"password\":\"S3cure!Passw0rd\"}".formatted(email)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    // ---------------- Authentication & profile ----------------

    @Test
    void applicationStartsWithMongoAndRejectsUnauthenticatedProfileAccess() throws Exception {
        // Context booted (Mongo reachable); protected endpoint must reject anonymous access
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/incidents/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedOwnerCanReadAndUpdateTheirProfile() throws Exception {
        String email = uniqueEmail(), phone = uniquePhone();
        String token = registerVerifyLogin(email, phone);

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.emergencyProfile.infoSource").value("USER_PROVIDED"));

        mockMvc.perform(put("/api/v1/users/me/emergency-profile")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"dateOfBirth":"1999-05-04","bloodGroup":"AB-",
                     "allergies":["Peanuts"],"medicalConditions":["Asthma"],
                     "currentMedications":["Inhaler"]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bloodGroup").value("AB-"))
            .andExpect(jsonPath("$.allergies[0]").value("Peanuts"));

        mockMvc.perform(get("/api/v1/users/me/emergency-profile").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dateOfBirth").value("1999-05-04"))
            .andExpect(jsonPath("$.medicalConditions[0]").value("Asthma"));
    }

    @Test
    void invalidProfilePayloadReturnsFieldValidationErrors() throws Exception {
        String token = registerVerifyLogin(uniqueEmail(), uniquePhone());
        mockMvc.perform(put("/api/v1/users/me/emergency-profile")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bloodGroup\":\"Z+\",\"dateOfBirth\":\"not-a-date\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.bloodGroup").exists())
            .andExpect(jsonPath("$.fieldErrors.dateOfBirth").exists());
    }

    // ---------------- Contacts ----------------

    @Test
    void contactCrudRoundTripForAuthenticatedOwner() throws Exception {
        String token = registerVerifyLogin(uniqueEmail(), uniquePhone());

        MvcResult created = mockMvc.perform(post("/api/v1/users/me/emergency-contacts")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Meera\",\"phone\":\"+919812345678\",\"relationship\":\"Sibling\",\"primary\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contactId").exists())
            .andExpect(jsonPath("$.name").value("Meera"))
            .andReturn();
        String contactId = objectMapper.readTree(created.getResponse().getContentAsString())
            .get("contactId").asText();

        mockMvc.perform(get("/api/v1/users/me/emergency-contacts").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts.length()").value(1));

        mockMvc.perform(put("/api/v1/users/me/emergency-contacts/" + contactId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Meera S\",\"phone\":\"+919812345678\",\"relationship\":\"Guardian\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Meera S"))
            .andExpect(jsonPath("$.relationship").value("Guardian"));

        mockMvc.perform(delete("/api/v1/users/me/emergency-contacts/" + contactId)
                .header("Authorization", bearer(token)))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me/emergency-contacts").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts.length()").value(0));
    }

    @Test
    void oneUserCannotReadOrUpdateOrDeleteAnotherUsersContact() throws Exception {
        String tokenA = registerVerifyLogin(uniqueEmail(), uniquePhone());
        String tokenB = registerVerifyLogin(uniqueEmail(), uniquePhone());

        MvcResult created = mockMvc.perform(post("/api/v1/users/me/emergency-contacts")
                .header("Authorization", bearer(tokenA))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Private Contact\",\"phone\":\"+919800011122\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        String contactId = objectMapper.readTree(created.getResponse().getContentAsString())
            .get("contactId").asText();

        // User B cannot see, update, or delete user A's contact
        mockMvc.perform(get("/api/v1/users/me/emergency-contacts").header("Authorization", bearer(tokenB)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts.length()").value(0));

        mockMvc.perform(put("/api/v1/users/me/emergency-contacts/" + contactId)
                .header("Authorization", bearer(tokenB))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hijacked\",\"phone\":\"+919800011122\"}"))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/users/me/emergency-contacts/" + contactId)
                .header("Authorization", bearer(tokenB)))
            .andExpect(status().isNotFound());

        // A's contact is untouched
        mockMvc.perform(get("/api/v1/users/me/emergency-contacts").header("Authorization", bearer(tokenA)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contacts[0].name").value("Private Contact"));
    }

    // ---------------- Incidents ----------------

    private String incidentBody(String incidentId) {
        return """
            {"incidentId":"%s","category":"SOS_BUTTON","clientCreatedAt":1700000000000,
             "source":"SOS Button (Press & Hold)","description":"SOS activated",
             "latitude":16.7050,"longitude":74.2433,"locationAccuracyMeters":8.0,
             "locationCapturedAt":1699999999000}
            """.formatted(incidentId);
    }

    @Test
    void incidentCreationIsIdempotentAndMineIsPaginated() throws Exception {
        String token = registerVerifyLogin(uniqueEmail(), uniquePhone());
        String id1 = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/incidents").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(incidentBody(id1)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.incidentId").value(id1))
            .andExpect(jsonPath("$.duplicate").value(false))
            .andExpect(jsonPath("$.status").value("OPEN"));

        // Retry the SAME upload: no duplicate is created
        mockMvc.perform(post("/api/v1/incidents").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(incidentBody(id1)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(true));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/incidents").header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(incidentBody(UUID.randomUUID().toString())))
                .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/incidents/mine?size=2&page=0").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(4))
            .andExpect(jsonPath("$.content.length()").value(2));

        mockMvc.perform(get("/api/v1/incidents/" + id1).header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.category").value("SOS_BUTTON"))
            .andExpect(jsonPath("$.latitude").value(16.7050));
    }

    @Test
    void batchSyncHandlesMixedNewAndDuplicateIncidents() throws Exception {
        String token = registerVerifyLogin(uniqueEmail(), uniquePhone());
        String knownId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/incidents").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(incidentBody(knownId)))
            .andExpect(status().isCreated());

        String batch = """
            {"incidents":[
              {"incidentId":"%s","category":"SOS_BUTTON","clientCreatedAt":1700000000000},
              {"incidentId":"%s","category":"HARDWARE_TRIGGER_SOS","clientCreatedAt":1700000010000},
              {"incidentId":"","category":"BAD","clientCreatedAt":1700000020000}
            ]}
            """.formatted(knownId, UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/sync/incidents").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(batch))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1))
            .andExpect(jsonPath("$.duplicates").value(1))
            .andExpect(jsonPath("$.rejected").value(1))
            .andExpect(jsonPath("$.results.length()").value(3));
    }

    @Test
    void incidentUploadRequiresAuthentication() throws Exception {
        String id = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/incidents")
                .contentType(MediaType.APPLICATION_JSON).content(incidentBody(id)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void userBCannotReadUserAIncident() throws Exception {
        String tokenA = registerVerifyLogin(uniqueEmail(), uniquePhone());
        String tokenB = registerVerifyLogin(uniqueEmail(), uniquePhone());
        String id = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/incidents").header("Authorization", bearer(tokenA))
                .contentType(MediaType.APPLICATION_JSON).content(incidentBody(id)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/incidents/" + id).header("Authorization", bearer(tokenB)))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/incidents/mine").header("Authorization", bearer(tokenB)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void invalidIncidentPayloadReturnsValidationErrors() throws Exception {
        String token = registerVerifyLogin(uniqueEmail(), uniquePhone());
        mockMvc.perform(post("/api/v1/incidents").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"incidentId":"not-a-uuid","category":"","clientCreatedAt":null,
                     "latitude":200.0}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.incidentId").exists())
            .andExpect(jsonPath("$.fieldErrors.latitude").exists());
    }

    @Test
    void duplicateRegistrationStillConflictsAfterIndexCreation() throws Exception {
        String email = uniqueEmail(), phone = uniquePhone();
        String token = registerVerifyLogin(email, phone);
        assertThat(token).isNotBlank();

        mockMvc.perform(post("/api/v1/auth/register")
                .with(req -> { req.setRemoteAddr("10.9.9.9"); return req; })
                .contentType(MediaType.APPLICATION_JSON).content(registerBody(email, phone)))
            .andExpect(status().isConflict());
    }
}
