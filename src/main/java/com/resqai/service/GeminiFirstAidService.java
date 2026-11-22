package com.resqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resqai.model.EmergencyVoiceSessionDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini-powered conversation brain for the browser-based AI first-aid agent
 * (user direction: use THEIR Gemini API for the AI conversation).
 *
 * Safety architecture (spec PART 5/9):
 *  - The reviewed EmergencyFirstAidService knowledge base is the SOURCE OF
 *    TRUTH: its digest + hard rules go into the system instruction, and every
 *    Gemini reply is POST-VALIDATED against the prohibited-pattern list. A
 *    violating or empty reply is replaced by the deterministic fallback.
 *  - The assistant never diagnoses, prescribes, or guarantees outcomes; it
 *    never delays emergency services; it always speaks as an AI.
 *  - If Gemini is unavailable or the key is missing, the handler falls back
 *    to the deterministic conversation engine - the emergency never stalls.
 */
@Service
public class GeminiFirstAidService {

    private static final Logger log = LoggerFactory.getLogger(GeminiFirstAidService.class);
    private static final String API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private final ProviderHttpClient http;
    private final EmergencyFirstAidService firstAidKb;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;

    public GeminiFirstAidService(
            ProviderHttpClient http,
            EmergencyFirstAidService firstAidKb,
            @Value("${resqai.gemini.api-key:}") String apiKey,
            @Value("${resqai.gemini.model:gemini-2.0-flash}") String model) {
        this.http = http;
        this.firstAidKb = firstAidKb;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "gemini-2.0-flash" : model.trim();
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /** Models listed by the key's account, resolved once (Google rotates
     *  model names - a hardcoded name 404s, as gemini-2.0-flash did). */
    private volatile String resolvedModel;

    private String resolveModel() {
        String chosen = resolvedModel;
        if (chosen != null) return chosen;
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models";
            ProviderHttpClient.ProviderResponse response = http.send(
                "GET", url, "application/json", null, new byte[0]);
            if (response.statusCode >= 200 && response.statusCode < 300) {
                JsonNode models = mapper.readTree(response.body).path("models");
                List<String> flashes = new java.util.ArrayList<>();
                models.forEach(m -> {
                    String name = m.path("name").asText("");
                    boolean generateOk = false;
                    for (JsonNode method : m.path("supportedGenerationMethods")) {
                        if ("generateContent".equals(method.asText())) generateOk = true;
                    }
                    if (generateOk && name.contains("flash")) flashes.add(name);
                });
                // Preference: newest flash lineage first.
                chosen = flashes.stream()
                    .filter(n -> n.contains("gemini-2.5")).findFirst()
                    .or(() -> flashes.stream().filter(n -> n.contains("gemini-2.0")).findFirst())
                    .or(() -> flashes.stream().filter(n -> n.contains("gemini-1.5")).findFirst())
                    .orElse(null);
                if (chosen != null) {
                    chosen = chosen.replace("models/", "");
                    resolvedModel = chosen;
                    log.info("Gemini model resolved from ListModels: {}", chosen);
                }
            }
        } catch (Exception e) {
            log.warn("Gemini ListModels probe failed: {}", e.getMessage());
        }
        if (chosen == null) chosen = model; // fall back to the configured name
        return chosen;
    }

    public String model() {
        return model;
    }

    /**
     * One conversation turn: builds the restricted system instruction from
     * the KB + facts, sends the bystander's utterance, and returns a safe
     * reply text.
     */
    public String reply(EmergencyVoiceSessionDocument session, String category,
                        FirstAidOrchestrator.MedicalContext medical, String transcript) {
        String language = session.getLanguage() == null ? "en" : session.getLanguage();
        String systemInstruction = buildSystemInstruction(category, medical, language);
        String userContent = buildUserContent(session, transcript);

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("system_instruction", Map.of(
                "parts", List.of(Map.of("text", systemInstruction))));
            body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userContent)))));
            body.put("generationConfig", Map.of(
                "temperature", 0.3,
                "maxOutputTokens", 180));

            String url = String.format(API_URL, resolveModel());
            ProviderHttpClient.ProviderResponse response = http.send(
                "POST", url, "application/json", "api-subscription-key " + apiKey,
                mapper.writeValueAsBytes(body));

            if (response.statusCode == 400 || response.statusCode == 403
                || response.statusCode == 429) {
                // Quota/permission problems: honest upstream handling.
                log.warn("Gemini rejected the turn (HTTP {})", response.statusCode);
                return null;
            }
            if (response.statusCode < 200 || response.statusCode >= 300) {
                log.warn("Gemini HTTP {}", response.statusCode);
                return null;
            }
            JsonNode root = mapper.readTree(response.body);
            String text = root.path("candidates").path(0).path("content")
                .path("parts").path(0).path("text").asText("").trim();
            if (text.isEmpty()) return null;

            // Safety post-validation: a violating reply is discarded.
            if (firstAidKb.violatesSafety(text)) {
                log.warn("Gemini reply violated safety patterns - discarded");
                return null;
            }
            return text;
        } catch (Exception e) {
            log.warn("Gemini turn failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * FULL AUDIO TURN (spec PART 7, Option B): the bystander's spoken
     * utterance (WAV) goes straight to Gemini - it transcribes the speech
     * AND writes the first-aid response in ONE call. The system instruction
     * enforces English replies and every safety rule. Returns
     * {"transcript":..., "reply":..., "urgent":...} or null on failure.
     */
    public AudioTurnResult audioTurn(EmergencyVoiceSessionDocument session,
                                     String category,
                                     FirstAidOrchestrator.MedicalContext medical,
                                     byte[] wavBytes) {
        if (!isConfigured() || wavBytes == null || wavBytes.length < 1000) return null;
        String language = session.getLanguage() == null ? "en" : session.getLanguage();
        try {
            String systemInstruction = buildSystemInstruction(category, medical, language);
            String b64 = Base64.getEncoder().encodeToString(wavBytes);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("system_instruction", Map.of(
                "parts", List.of(Map.of("text",
                    systemInstruction + "\n\nFirst TRANSCRIBE the bystander's speech verbatim, "
                    + "then respond as the emergency assistant. Reply with ONLY a JSON object: "
                    + "{\"transcript\": \"<verbatim English transcript>\", "
                    + "\"reply\": \"<your spoken response in English, max 60 words\", "
                    + "\"conscious\": \"yes|no|unknown\", "
                    + "\"breathing\": \"yes|no|unknown\", "
                    + "\"bleeding\": \"yes|no|unknown\", "
                    + "\"urgent\": true|false}"))));
            body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of(
                    "inline_data", Map.of("mime_type", "audio/wav", "data", b64))))));
            body.put("generationConfig", Map.of(
                "temperature", 0.3,
                "maxOutputTokens", 300));

            String url = String.format(API_URL, resolveModel());
            ProviderHttpClient.ProviderResponse response = http.send(
                "POST", url, "application/json", "api-subscription-key " + apiKey,
                mapper.writeValueAsBytes(body));
            if (response.statusCode < 200 || response.statusCode >= 300) {
                log.warn("Gemini audio turn HTTP {}: {}", response.statusCode,
                    response.body == null ? "" : response.body.substring(0, Math.min(100, response.body.length())));
                return null;
            }
            JsonNode root = mapper.readTree(response.body);
            String text = root.path("candidates").path(0).path("content")
                .path("parts").path(0).path("text").asText("").trim();
            if (text.isEmpty()) return null;
            // Strip markdown fences if the model adds them.
            text = text.replace("```json", "").replace("```", "").trim();
            JsonNode parsed = mapper.readTree(text);
            String transcript = parsed.path("transcript").asText("").trim();
            String reply = parsed.path("reply").asText("").trim();
            if (reply.isEmpty()) return null;
            // Safety post-validation on the reply.
            if (firstAidKb.violatesSafety(reply)) {
                log.warn("Gemini audio-turn reply violated safety patterns - discarded");
                return null;
            }
            // Merge reported facts into the session.
            Map<String, String> facts = session.getCollectedFacts();
            for (String key : List.of("conscious", "breathing", "bleeding")) {
                String v = parsed.path(key).asText("unknown");
                if (!"unknown".equals(v)) facts.put(key, v);
            }
            boolean urgent = parsed.path("urgent").asBoolean(false)
                || firstAidKb.isRedFlagUtterance(transcript);
            return new AudioTurnResult(transcript, reply, urgent);
        } catch (Exception e) {
            log.warn("Gemini audio turn failed: {}", e.getMessage());
            return null;
        }
    }

    public record AudioTurnResult(String transcript, String reply, boolean urgent) {}

    private String buildSystemInstruction(String category,
                                          FirstAidOrchestrator.MedicalContext medical,
                                          String lang) {
        String languageName = switch (lang) {
            case "hi" -> "Hindi (Devanagari script)";
            case "mr" -> "Marathi (Devanagari script)";
            default -> "English";
        };
        String blood = medical == null ? null : medical.bloodGroup();
        String allergies = medical == null ? null : medical.allergies();
        String conditions = (medical != null && medical.conditions() != null)
            ? String.join(", ", medical.conditions()) : null;

        StringBuilder sb = new StringBuilder();
        sb.append("You are ResQAI's automated emergency first-aid assistant for a 911-style ")
          .append("ambulance dispatch app. You ARE an AI - never claim to be human. ")
          .append("The ambulance has ALREADY been contacted; never delay it. ")
          .append("Reply ONLY in ").append(languageName).append(". ")
          .append("Keep every reply under 45 words: ONE calm instruction or ONE question at a time. ")
          .append("Never diagnose, never prescribe medicines or dosages, never recommend invasive ")
          .append("procedures, never guarantee outcomes, never induce vomiting, never put anything ")
          .append("in the mouth during a seizure, never move a suspected spinal injury. ")
          .append("If the patient is not breathing, give CPR guidance immediately. ")
          .append("Ask about: consciousness, breathing, severe bleeding, and category-specific checks. ")
          .append("Do not repeat questions you already have answers to.\n")
          .append("Reported emergency category: ").append(category).append(".\n")
          .append("Patient name/blood/allergies/conditions as reported by registration: ")
          .append(blood == null ? "blood group unknown" : "blood group " + blood).append("; ")
          .append(allergies == null ? "no allergy data; " : "allergies: " + allergies + "; ")
          .append(conditions == null || conditions.isBlank() ? "no condition data. "
              : "conditions: " + conditions + ". ")
          .append("These are REGISTERED data, not clinically verified.\n")
          .append("Reviewed first-aid knowledge base:\n")
          .append(firstAidKb.promptDigest());
        return sb.toString();
    }

    private String buildUserContent(EmergencyVoiceSessionDocument session, String transcript) {
        StringBuilder sb = new StringBuilder();
        Map<String, String> facts = session.getCollectedFacts() == null
            ? Map.of() : session.getCollectedFacts();
        if (!facts.isEmpty()) {
            sb.append("Facts collected so far: ");
            for (Map.Entry<String, String> e : facts.entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append("; ");
            }
            sb.append("\n");
        }
        if (!session.getObservations().isEmpty()) {
            sb.append("Recent bystander statements: ");
            int last = Math.min(4, session.getObservations().size());
            int start = session.getObservations().size() - last;
            for (int i = start; i < session.getObservations().size(); i++) {
                sb.append('"').append(session.getObservations().get(i)).append("\"; ");
            }
            sb.append("\n");
        }
        sb.append("The bystander just said: \"")
          .append(transcript == null ? "" : transcript).append("\". Respond now.");
        return sb.toString();
    }
}
