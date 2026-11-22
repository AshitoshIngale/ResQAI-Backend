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
 * Gemini TTS fallback voice (spec: the conversation must never go silent).
 * Uses the Gemini API's native speech generation (responseModalities AUDIO)
 * and converts the returned PCM to 16-bit WAV the browser can stream.
 * Sarvam streaming TTS remains the primary voice; this is the fallback when
 * Sarvam is out of credits or unavailable.
 */
@Service
public class GeminiTtsService {

    private static final Logger log = LoggerFactory.getLogger(GeminiTtsService.class);
    private static final String API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private final ProviderHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final String voiceName;

    public GeminiTtsService(
            @Qualifier("geminiHttp") ProviderHttpClient http,
            @Value("${resqai.gemini.api-key:}") String apiKey,
            @Value("${resqai.gemini.tts-model:gemini-2.5-flash-preview-tts}") String model,
            @Value("${resqai.gemini.tts-voice:Kore}") String voiceName) {
        this.http = http;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "gemini-2.5-flash-preview-tts" : model.trim();
        this.voiceName = voiceName == null || voiceName.isBlank() ? "Kore" : voiceName.trim();
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    public record TtsResult(byte[] wav, String detail) {}

    /** One-shot synthesis: text -> PCM24k float -> 16-bit WAV. Null on failure. */
    public TtsResult synthesize(String text, String languageHint) {
        if (apiKey.isBlank()) return null;
        if (text == null || text.isBlank()) return null;
        if (text.length() > 1200) text = text.substring(0, 1200);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", List.of(Map.of("parts",
                List.of(Map.of("text", text)))));
            body.put("generationConfig", Map.of(
                "responseModalities", List.of("AUDIO"),
                "speechConfig", Map.of("voiceConfig", Map.of(
                    "prebuiltVoiceConfig", Map.of("voiceName", voiceName)))));

            String url = String.format(API_URL, model) + "?key=" + apiKey;
            ProviderHttpClient.ProviderResponse response = http.send(
                "POST", url, "application/json", null,
                mapper.writeValueAsBytes(body));
            if (response.statusCode < 200 || response.statusCode >= 300) {
                log.warn("Gemini TTS HTTP {}: {}", response.statusCode,
                    response.body == null ? "" : response.body.substring(0, Math.min(120, response.body.length())));
                return null;
            }
            JsonNode root = mapper.readTree(response.body);
            JsonNode part = root.path("candidates").path(0).path("content")
                .path("parts").path(0);
            String mime = part.path("inlineData").path("mimeType").asText("");
            String b64 = part.path("inlineData").path("data").asText("");
            if (b64.isBlank()) return null;
            byte[] raw = java.util.Base64.getDecoder().decode(b64);
            int rate = parseRate(mime, 24000);
            // Gemini TTS variants differ: some return a COMPLETE WAV, some raw
            // 16-bit PCM (audio/L16), some 32-bit float PCM. Handle each shape
            // explicitly - a wrong assumption crashes the whole reply voice.
            String lower = mime == null ? "" : mime.toLowerCase();
            byte[] wav;
            if (lower.contains("wav")) {
                wav = raw;                       // already a full WAV file
            } else if (lower.contains("l16") || lower.contains("pcm")) {
                wav = pcm16ToWav(raw, rate);     // raw 16-bit LE PCM samples
            } else {
                wav = float32ToPcm16Wav(raw, rate);
            }
            return new TtsResult(wav, "gemini-tts:" + voiceName + "@" + rate
                + " (" + (mime.isBlank() ? "unknown" : mime) + ")");
        } catch (Exception e) {
            log.warn("Gemini TTS failed: {}", e.getMessage());
            return null;
        }
    }

    private static int parseRate(String mime, int fallback) {
        if (mime == null) return fallback;
        var m = java.util.regex.Pattern.compile("rate=(\\d+)").matcher(mime);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    /** Raw 16-bit LE PCM samples -> 16-bit PCM WAV (header + verbatim data). */
    static byte[] pcm16ToWav(byte[] pcm, int sampleRate) {
        byte[] out = new byte[44 + pcm.length];
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        writeInt(out, 4, 36 + pcm.length);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        writeInt(out, 16, 16);
        out[20] = 1; out[21] = 0;                     // PCM
        out[22] = 1; out[23] = 0;                     // mono
        writeInt(out, 24, sampleRate);
        writeInt(out, 28, sampleRate * 2);
        out[32] = 2; out[33] = 0;
        out[34] = 16; out[35] = 0;
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        writeInt(out, 40, pcm.length);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }

    /** 32-bit float LE samples -> 16-bit PCM WAV (simple, clamped). */
    private static byte[] float32ToPcm16Wav(byte[] floatPcm, int sampleRate) {
        int floatCount = floatPcm.length / 4;
        byte[] out = new byte[44 + floatCount * 2];
        // WAV header
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        writeInt(out, 4, 36 + floatCount * 2);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        writeInt(out, 16, 16);
        out[20] = 1; out[21] = 0;                     // PCM
        out[22] = 1; out[23] = 0;                     // mono
        writeInt(out, 24, sampleRate);
        writeInt(out, 28, sampleRate * 2);
        out[32] = 2; out[33] = 0;
        out[34] = 16; out[35] = 0;
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        writeInt(out, 40, floatCount * 2);
        for (int i = 0; i < floatCount; i++) {
            int off = i * 4;
            int bits = (floatPcm[off] & 0xFF)
                | ((floatPcm[off + 1] & 0xFF) << 8)
                | ((floatPcm[off + 2] & 0xFF) << 16)
                | ((floatPcm[off + 3] & 0xFF) << 24);
            float sample = Float.intBitsToFloat(bits);
            int v = Math.round(sample * 32767f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            out[44 + i * 2] = (byte) v;
            out[45 + i * 2] = (byte) (v >> 8);
        }
        return out;
    }

    private static void writeInt(byte[] b, int off, int value) {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >> 8);
        b[off + 2] = (byte) (value >> 16);
        b[off + 3] = (byte) (value >> 24);
    }
}
