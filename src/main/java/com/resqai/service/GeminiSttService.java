package com.resqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini-powered transcription (Sarvam removed entirely per user decision):
 * the bystander's voice note or utterance WAV goes to the user's own Gemini
 * API and comes back as a verbatim transcript in one call.
 */
@Service
public class GeminiSttService {

    private static final Logger log = LoggerFactory.getLogger(GeminiSttService.class);
    private static final String API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    /** Same retirement-safe preference as the conversation brain. */
    static final List<String> MODEL_PREFERENCE = List.of(
        "gemini-flash-lite-latest",       // fast, cheap, verified live
        "gemini-flash-latest",
        "gemini-2.5-flash");

    public record TranscriptionResult(String transcript, String detail) {}

    private final ProviderHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    private volatile String resolvedModel;
    private volatile int candidateIndex = 0;

    public GeminiSttService(
            @Qualifier("geminiHttp") ProviderHttpClient http,
            @Value("${resqai.gemini.api-key:}") String apiKey) {
        this.http = http;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /**
     * Transcribes speech audio (WAV bytes) verbatim. Returns a result with a
     * blank transcript and honest detail on any failure.
     */
    public TranscriptionResult transcribe(byte[] wavBytes, String languageHint) {
        if (!isConfigured()) {
            return new TranscriptionResult("", "Gemini not configured");
        }
        if (wavBytes == null || wavBytes.length < 1000) {
            return new TranscriptionResult("", "audio too short to transcribe");
        }
        try {
            String b64 = java.util.Base64.getEncoder().encodeToString(wavBytes);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("system_instruction", Map.of("parts", List.of(Map.of("text",
                "You are a medical-grade transcriber. Transcribe the speech in this "
                + "audio VERBATIM, in the language actually spoken"
                + (languageHint == null || languageHint.isBlank()
                    ? "" : " (expected language: " + languageHint + ")")
                + ". Output ONLY the transcript text - no commentary, no quotes."))));
            body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of(
                    "inline_data", Map.of("mime_type", "audio/wav", "data", b64))))));
            body.put("generationConfig", Map.of(
                "temperature", 0.0,
                "maxOutputTokens", 400));

            ProviderHttpClient.ProviderResponse response = null;
            for (int attempt = 0; attempt < MODEL_PREFERENCE.size(); attempt++) {
                String url = String.format(API_URL, resolveModel()) + "?key=" + apiKey;
                response = http.send("POST", url, "application/json", null,
                    mapper.writeValueAsBytes(body));
                if (response.statusCode == 404 && response.body.contains("no longer available")) {
                    advancePastRetired(resolveModel());
                    continue;
                }
                break;
            }
            if (response.statusCode < 200 || response.statusCode >= 300) {
                log.warn("Gemini STT HTTP {}", response.statusCode);
                return new TranscriptionResult("", "transcription rejected (HTTP "
                    + response.statusCode + ")");
            }
            JsonNode root = mapper.readTree(response.body);
            String text = root.path("candidates").path(0).path("content")
                .path("parts").path(0).path("text").asText("").trim();
            if (text.isEmpty()) {
                return new TranscriptionResult("", "no speech recognized");
            }
            // Models occasionally wrap the transcript in quotes or fences.
            text = text.replace("```", "").trim();
            if (text.startsWith("\"") && text.endsWith("\"") && text.length() > 1) {
                text = text.substring(1, text.length() - 1).trim();
            }
            return new TranscriptionResult(text, "gemini-stt");
        } catch (Exception e) {
            log.warn("Gemini STT failed: {}", e.getMessage());
            return new TranscriptionResult("", "transcription error (details omitted)");
        }
    }

    private String resolveModel() {
        String chosen = resolvedModel;
        if (chosen != null) return chosen;
        int idx = Math.min(candidateIndex, MODEL_PREFERENCE.size() - 1);
        chosen = MODEL_PREFERENCE.get(idx);
        resolvedModel = chosen;
        log.info("Gemini STT model resolved: {}", chosen);
        return chosen;
    }

    private void advancePastRetired(String deadModel) {
        int at = MODEL_PREFERENCE.indexOf(deadModel);
        candidateIndex = Math.max(0, Math.min(at + 1, MODEL_PREFERENCE.size() - 1));
        resolvedModel = null;
        log.warn("Gemini STT model {} retired (404) - advancing to {}",
            deadModel, MODEL_PREFERENCE.get(candidateIndex));
    }
}
