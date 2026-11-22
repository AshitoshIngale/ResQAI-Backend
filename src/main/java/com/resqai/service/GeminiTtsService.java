package com.resqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * THE speech service of ResQAI (Sarvam removed entirely per user decision):
 * every spoken reply and every ambulance-call segment is synthesized with the
 * user's own Gemini API. The newer 3.8 TTS voices (Charon) sound markedly
 * more human than the old 2.5 preview, and each synthesis carries an explicit
 * languageCode so Hindi/Marathi are pronounced natively instead of
 * transliterated. The 3.8 models are read-aloud: NO style prefixes (they
 * would be narrated aloud) - see the note in synthesize().
 *
 * Dual API surface:
 *  - one-shot speech for the AI voice conversation (returns audio or null;
 *    the caller falls back to the app's offline TTS on failure);
 *  - a caching surface for the Twilio TwiML call flow (prewarm during the
 *    ring, cache-only fetches inside the webhook - the webhook NEVER waits
 *    on the network, exactly like the previous Sarvam contract).
 */
@Service
public class GeminiTtsService {

    private static final Logger log = LoggerFactory.getLogger(GeminiTtsService.class);
    private static final String API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    /** Google RETIRES model names aggressively (2.5/2.0-flash both started
     *  404ing while still listed) - a call-time 404 advances the candidate. */
    static final List<String> MODEL_PREFERENCE = List.of(
        "gemini-3.8-flash-tts",           // newest full-quality voice (verified)
        "gemini-3.8-flash-lite-tts",
        "gemini-2.5-flash-preview-tts");

    public enum Status { NOT_CONFIGURED, SYNTHESIZED, FAILED, RETIRED_MODEL }

    public record TtsResult(Status status, byte[] audioWav, String detail, Instant attemptedAt) {}

    private final ProviderHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String voiceName;

    /** TwiML speech cache: cacheKey -> complete WAV bytes. */
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    private final ExecutorService warmer = Executors.newFixedThreadPool(3);

    private volatile String resolvedModel;
    private volatile int candidateIndex = 0;

    public GeminiTtsService(
            @Qualifier("geminiHttp") ProviderHttpClient http,
            @Value("${resqai.gemini.api-key:}") String apiKey,
            @Value("${resqai.gemini.tts-model:}") String model,
            @Value("${resqai.gemini.tts-voice:Charon}") String voiceName) {
        this.http = http;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        // A dashboard-configured model joins the front of the preference list;
        // blank keeps the verified built-in order. Retired names still
        // self-heal via the call-time 404 path.
        this.configuredModel = model == null ? "" : model.trim();
        this.voiceName = voiceName == null || voiceName.isBlank() ? "Charon" : voiceName.trim();
    }

    private final String configuredModel;

    private List<String> preference() {
        if (configuredModel.isBlank()) return MODEL_PREFERENCE;
        return new java.util.ArrayList<>(
            java.util.stream.Stream.concat(java.util.stream.Stream.of(configuredModel),
                MODEL_PREFERENCE.stream()).toList());
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    // ------------------------------------------------------------------
    // Conversation voice (WS handler): one-shot, style-directed speech.
    // ------------------------------------------------------------------

    /**
     * Synthesizes ONE spoken reply. Returns the complete WAV, or null when
     * the key is missing/Google fails - the caller then delivers the text to
     * the app's offline TTS (ai_text), so the reply is never lost.
     */
    public byte[] synthesizeReply(String text, String languageHint) {
        if (apiKey.isBlank() || text == null || text.isBlank()) return null;
        TtsResult r = synthesize(text, languageHint);
        return r.status() == Status.SYNTHESIZED ? r.audioWav() : null;
    }

    // ------------------------------------------------------------------
    // TwiML call flow: prewarm + cache-only contract (webhook never waits).
    // ------------------------------------------------------------------

    public byte[] cachedAudio(String cacheKey) {
        return cache.get(cacheKey);
    }

    public TtsResult synthesizeCachedOnly(String text, String languageCode) {
        byte[] wav = cache.get(cacheKey(text, languageCode));
        if (wav != null) {
            return new TtsResult(Status.SYNTHESIZED, wav, "cached", Instant.now());
        }
        return new TtsResult(Status.FAILED, null, "not prewarmed", Instant.now());
    }

    /** Best-effort parallel warm-up during the ring (Twilio waits ~15 s,
     *  enough for one synthesis round on the 3.8 models). */
    public void prewarm(Iterable<String[]> textAndLanguagePairs) {
        if (!isConfigured()) return;
        for (String[] pair : textAndLanguagePairs) {
            if (pair == null || pair.length < 2) continue;
            String text = pair[0];
            String lang = pair[1];
            if (cache.containsKey(cacheKey(text, lang))) continue;
            warmer.submit(() -> synthesize(text, lang));
        }
    }

    public static String cacheKey(String text, String languageCode) {
        return languageCode + ":" + Integer.toHexString(
            (text == null ? "" : text).hashCode());
    }

    /**
     * Full synthesis with the style instruction + language config; stores the
     * result in the cache (harmless for conversation text, essential for the
     * TwiML segments). NEVER throws.
     */
    public TtsResult synthesize(String text, String languageCode) {
        if (apiKey.isBlank()) {
            return new TtsResult(Status.NOT_CONFIGURED, null, "no key", Instant.now());
        }
        if (text == null || text.isBlank()) {
            return new TtsResult(Status.FAILED, null, "empty text", Instant.now());
        }
        String clipped = text.length() > 1200 ? text.substring(0, 1200) : text;
        String lang = normalizeLanguage(languageCode);
        String key = cacheKey(text, languageCode);
        byte[] cached = cache.get(key);
        if (cached != null) {
            return new TtsResult(Status.SYNTHESIZED, cached, "cached", Instant.now());
        }
        try {
            Map<String, Object> speechConfig = new LinkedHashMap<>();
            speechConfig.put("languageCode", lang);
            speechConfig.put("voiceConfig", Map.of("prebuiltVoiceConfig",
                Map.of("voiceName", voiceName)));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("generationConfig", Map.of(
                "responseModalities", List.of("AUDIO"),
                "speechConfig", speechConfig));

            ProviderHttpClient.ProviderResponse response = null;
            String model = null;
            List<String> pref = preference();
            int start = Math.min(candidateIndex, pref.size() - 1);
            for (int idx = start; idx < pref.size(); idx++) {
                model = pref.get(idx);
                // VERIFIED behavior difference (2026-11-22, transcribed the
                // audio back): the 3.8 TTS models SPEAK THE ENTIRE PROMPT -
                // a style prefix is narrated aloud before the reply. They
                // are read-aloud, single-turn models (system_instruction and
                // multi-turn context are rejected with 400). So they get
                // PLAIN text. Only the older 2.5-preview models honor the
                // documented "Say <style>: <text>" prefix silently.
                boolean honorsStylePrefix = model.contains("2.5-flash-preview-tts")
                    || model.contains("pro-preview-tts");
                String spoken = honorsStylePrefix ? stylePrompt(lang) + clipped : clipped;
                body.put("contents", List.of(Map.of("parts",
                    List.of(Map.of("text", spoken)))));
                String url = String.format(API_URL, model) + "?key=" + apiKey;
                response = http.send(
                    "POST", url, "application/json", null,
                    mapper.writeValueAsBytes(body));
                if (response.statusCode == 404 && response.body.contains("no longer available")) {
                    advancePastRetired(model); // permanent: model is gone
                    continue;
                }
                if (response.statusCode == 429) {
                    // Transient per-model quota: fail over to the next model
                    // for THIS request without permanently demoting it.
                    log.warn("Gemini TTS {} rate-limited (429) - trying the next model", model);
                    continue;
                }
                break;
            }
            if (response.statusCode < 200 || response.statusCode >= 300) {
                log.warn("Gemini TTS HTTP {}: {}", response.statusCode,
                    response.body == null ? "" : response.body.substring(0, Math.min(120, response.body.length())));
                return new TtsResult(Status.FAILED, null, "HTTP " + response.statusCode, Instant.now());
            }
            JsonNode root = mapper.readTree(response.body);
            JsonNode part = root.path("candidates").path(0).path("content")
                .path("parts").path(0);
            String mime = part.path("inlineData").path("mimeType").asText("");
            String b64 = part.path("inlineData").path("data").asText("");
            if (b64.isBlank()) return new TtsResult(Status.FAILED, null, "no audio", Instant.now());
            byte[] raw = java.util.Base64.getDecoder().decode(b64);
            int rate = parseRate(mime, 24000);
            // Gemini TTS variants differ: 3.8 models return a COMPLETE WAV,
            // 2.5-preview returns raw 16-bit PCM (audio/L16). Handle each.
            String lower = mime == null ? "" : mime.toLowerCase();
            byte[] wav;
            if (lower.contains("wav")) {
                wav = wavRate(raw, rate);
            } else if (lower.contains("l16") || lower.contains("pcm")) {
                wav = wavRate(pcm16ToWav(raw, rate), rate);
            } else {
                wav = wavRate(float32ToPcm16Wav(raw, rate), rate);
            }
            // Simple bound: the TwiML flow stores a handful of fixed
            // segments; conversation texts are transient. Never let this
            // grow without limit on a long-running instance.
            if (cache.size() >= 200) cache.clear();
            cache.put(key, wav);
            return new TtsResult(Status.SYNTHESIZED, wav,
                "gemini-tts:" + voiceName + "@" + model, Instant.now());
        } catch (Exception e) {
            log.warn("Gemini TTS failed: {}", e.getMessage());
            return new TtsResult(Status.FAILED, null, "error (details omitted)", Instant.now());
        }
    }

    private void advancePastRetired(String deadModel) {
        List<String> pref = preference();
        int at = pref.indexOf(deadModel);
        candidateIndex = Math.max(0, Math.min(at + 1, pref.size() - 1));
        resolvedModel = null;
        log.warn("Gemini TTS model {} retired (404) - advancing to {}",
            deadModel, pref.get(candidateIndex));
    }

    private static String normalizeLanguage(String code) {
        String c = code == null ? "en-IN" : code.trim().toLowerCase();
        return switch (c) {
            case "hi", "hi-in" -> "hi-IN";
            case "mr", "mr-in" -> "mr-IN";
            case "kn", "kn-in" -> "kn-IN";
            case "ta", "ta-in" -> "ta-IN";
            case "te", "te-in" -> "te-IN";
            case "bn", "bn-in" -> "bn-IN";
            case "gu", "gu-in" -> "gu-IN";
            case "pa", "pa-in" -> "pa-IN";
            case "ml", "ml-in" -> "ml-IN";
            default -> "en-IN";
        };
    }

    /** Style direction for the 2.5-preview TTS models only (they honor the
     *  documented prefix pattern silently). The 3.8 read-aloud models must
     *  NOT receive it - they narrate it aloud (verified by transcribing
     *  their own output). */
    private static String stylePrompt(String langCode) {
        boolean indic = langCode.startsWith("hi") || langCode.startsWith("mr")
            || langCode.startsWith("kn") || langCode.startsWith("ta")
            || langCode.startsWith("te") || langCode.startsWith("bn")
            || langCode.startsWith("gu") || langCode.startsWith("pa")
            || langCode.startsWith("ml");
        String base = "You are a warm, experienced emergency responder speaking to a "
            + "frightened bystander on the phone. Speak naturally like a real person: "
            + "calm, gentle and reassuring, at a normal conversational pace - not "
            + "slow, not rushed - with only brief natural pauses at the punctuation "
            + "and a soft rise at questions. Absolutely no robotic or flat delivery. ";
        if (indic) {
            base += "Pronounce the following text natively in its own language "
                + "with clear, careful enunciation. Say exactly: ";
        } else {
            base += "Say exactly: ";
        }
        return base;
    }

    private static int parseRate(String mime, int fallback) {
        if (mime == null) return fallback;
        var m = java.util.regex.Pattern.compile("rate=(\\d+)").matcher(mime);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    /**
     * Delivered speech is normalized to 16 kHz mono 16-bit WAV - the same
     * format the conversation uplink uses. A 24 kHz Google answer is
     * linearly resampled down: 33% smaller payloads over mobile networks
     * (a long reply at 24 kHz WAV base64s past 2 MB), with no perceptible
     * loss for phone speech.
     */
    static byte[] wavRate(byte[] completeWav, int rate) {
        if (rate == 16000 || completeWav.length <= 44) return completeWav;
        // Parse the WAV header: find the data chunk (handles LIST chunks).
        int dataOff = -1, dataLen = 0;
        int pos = 12;
        while (pos + 8 <= completeWav.length) {
            String id = new String(completeWav, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = (completeWav[pos + 4] & 0xFF) | ((completeWav[pos + 5] & 0xFF) << 8)
                | ((completeWav[pos + 6] & 0xFF) << 16) | ((completeWav[pos + 7] & 0xFF) << 24);
            if ("data".equals(id)) {
                dataOff = pos + 8;
                dataLen = Math.min(size, completeWav.length - dataOff);
                break;
            }
            pos += 8 + size + (size % 2);
        }
        if (dataOff < 0 || dataLen < 2) return completeWav;
        int samples = dataLen / 2;
        int outSamples = (int) ((long) samples * 16000 / rate);
        if (outSamples <= 0) return completeWav;
        byte[] out = new byte[44 + outSamples * 2];
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        writeInt(out, 4, 36 + outSamples * 2);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        writeInt(out, 16, 16);
        out[20] = 1; out[21] = 0;                     // PCM
        out[22] = 1; out[23] = 0;                     // mono
        writeInt(out, 24, 16000);
        writeInt(out, 28, 32000);
        out[32] = 2; out[33] = 0;
        out[34] = 16; out[35] = 0;
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        writeInt(out, 40, outSamples * 2);
        // Linear interpolation resample.
        double step = (double) (samples - 1) / Math.max(1, outSamples - 1);
        for (int i = 0; i < outSamples; i++) {
            double src = i * step;
            int i0 = (int) src;
            int i1 = Math.min(samples - 1, i0 + 1);
            double frac = src - i0;
            int s0 = (short) ((completeWav[dataOff + i0 * 2] & 0xFF)
                | (completeWav[dataOff + i0 * 2 + 1] << 8));
            int s1 = (short) ((completeWav[dataOff + i1 * 2] & 0xFF)
                | (completeWav[dataOff + i1 * 2 + 1] << 8));
            int v = (int) Math.round(s0 + (s1 - s0) * frac);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            out[44 + i * 2] = (byte) v;
            out[45 + i * 2] = (byte) (v >> 8);
        }
        return out;
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
