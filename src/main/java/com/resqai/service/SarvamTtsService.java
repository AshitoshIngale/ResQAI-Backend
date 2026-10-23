package com.resqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Sarvam text-to-speech for the emergency-call voice segments. Verified
 * against the live API (2026-10-24): POST https://api.sarvam.ai/text-to-speech
 * with Authorization: Bearer <key>, body {text, target_language_code, speaker},
 * default model bulbul:v3; response {"audios": ["<base64 wav>", ...]}.
 *
 * Honesty contract: NOT_CONFIGURED without a key; failures surface FAILED with
 * the provider reason — the call flow then falls back to Twilio's own <Say>
 * voices rather than claiming synthetic audio exists.
 */
@Service
public class SarvamTtsService {

    public enum Status { NOT_CONFIGURED, SYNTHESIZED, FAILED, INVALID_CREDENTIALS }

    public record TtsResult(Status status, byte[] audioWav, String detail, Instant attemptedAt) {}

    private static final Logger log = LoggerFactory.getLogger(SarvamTtsService.class);
    private static final String TTS_URL = "https://api.sarvam.ai/text-to-speech";
    /** Verified compatible speaker for all bulbul:v3 languages we use. */
    private static final String SPEAKER = "anand";

    private final ProviderHttpClient http;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Small bounded cache: incident scripts repeat across webhook retries. */
    private final ConcurrentMap<String, byte[]> cache = new ConcurrentHashMap<>();

    public SarvamTtsService(ProviderHttpClient http,
                            @Value("${resqai.sarvam.api-key:}") String apiKey) {
        this.http = http;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** Cached audio by key, for the signed webhook playback endpoint. */
    public byte[] cachedAudio(String cacheKey) {
        return cache.get(cacheKey);
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /** Cache key for a (text, language) pair — stable across webhook retries. */
    public static String cacheKey(String text, String languageCode) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(languageCode.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return String.valueOf((languageCode + text).hashCode());
        }
    }

    /**
     * Synthesizes one speech segment; cached per (text, language) so repeated
     * Twilio webhook fetches do not re-bill the provider.
     */
    public TtsResult synthesize(String text, String languageCode) {
        Instant attemptedAt = Instant.now();
        if (!isConfigured()) {
            return new TtsResult(Status.NOT_CONFIGURED, null,
                "Sarvam API key is not configured; synthetic speech unavailable.", attemptedAt);
        }
        if (text == null || text.isBlank()) {
            return new TtsResult(Status.FAILED, null, "Empty speech text.", attemptedAt);
        }
        String key = cacheKey(text, languageCode);
        byte[] cached = cache.get(key);
        if (cached != null) {
            return new TtsResult(Status.SYNTHESIZED, cached, "cache", attemptedAt);
        }
        try {
            String body = mapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("text", text);
                put("target_language_code", languageCode);
                put("speaker", SPEAKER);
            }});
            ProviderHttpClient.ProviderResponse response = http.send(
                "POST", TTS_URL, "application/json",
                "Bearer " + apiKey, body.getBytes(StandardCharsets.UTF_8));
            if (response.statusCode == 401 || response.statusCode == 403) {
                return new TtsResult(Status.INVALID_CREDENTIALS, null,
                    "Sarvam rejected the credentials (HTTP " + response.statusCode + ").", attemptedAt);
            }
            if (response.statusCode < 200 || response.statusCode >= 300) {
                return new TtsResult(Status.FAILED, null,
                    "Sarvam returned HTTP " + response.statusCode + ".", attemptedAt);
            }
            JsonNode json = mapper.readTree(response.body);
            JsonNode audios = json.path("audios");
            if (!audios.isArray() || audios.isEmpty() || audios.get(0).isNull()) {
                return new TtsResult(Status.FAILED, null,
                    "Sarvam returned no audio for this text.", attemptedAt);
            }
            byte[] wav = java.util.Base64.getDecoder().decode(audios.get(0).asText());
            if (cache.size() > 200) cache.clear(); // crude bound; scripts are short-lived
            cache.put(key, wav);
            return new TtsResult(Status.SYNTHESIZED, wav, "sarvam:tts:bulbul-v3", attemptedAt);
        } catch (Exception e) {
            log.warn("Sarvam TTS failed: {}", e.getMessage());
            return new TtsResult(Status.FAILED, null,
                "Speech synthesis request failed (network/timeout).", attemptedAt);
        }
    }
}
