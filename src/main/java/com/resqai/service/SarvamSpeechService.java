package com.resqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Sarvam speech-to-text for bystander voice notes. The AUDIO itself never
 * touches our database — only the returned transcript text is stored, and
 * only the short transcript (limited information) is ever persisted.
 *
 * Honesty contract: NOT_CONFIGURED when no API key is present; transcription
 * failures surface FAILED with the provider status — never a fabricated
 * transcript.
 */
@Service
public class SarvamSpeechService {

    public enum Status { NOT_CONFIGURED, TRANSCRIBED, FAILED, INVALID_CREDENTIALS }

    public record TranscriptionResult(
        Status status,
        String transcript,
        String detail,
        Instant attemptedAt
    ) {}

    private static final Logger log = LoggerFactory.getLogger(SarvamSpeechService.class);
    private static final String SARVAM_STT_URL = "https://api.sarvam.ai/speech-to-text";
    private static final String BOUNDARY = "resqai-" + UUID.randomUUID();

    private final ProviderHttpClient http;
    private final String apiKey;
    private final String sttModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public SarvamSpeechService(ProviderHttpClient http,
                               @Value("${resqai.sarvam.api-key:}") String apiKey) {
        this(http, apiKey, "saaras:v3");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SarvamSpeechService(ProviderHttpClient http,
                               @Value("${resqai.sarvam.api-key:}") String apiKey,
                               @Value("${resqai.sarvam.stt-model:saaras:v3}") String sttModel) {
        this.http = http;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.sttModel = sttModel == null || sttModel.isBlank() ? "saaras:v3" : sttModel.trim();
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /** Base64-decodes the caller's audio clip and asks Sarvam to transcribe it. */
    public TranscriptionResult transcribe(byte[] audioBytes, String languageCode) {
        Instant attemptedAt = Instant.now();
        if (!isConfigured()) {
            return new TranscriptionResult(Status.NOT_CONFIGURED, null,
                "Sarvam API key is not configured on the backend; voice notes cannot be transcribed.",
                attemptedAt);
        }
        if (audioBytes == null || audioBytes.length == 0) {
            return new TranscriptionResult(Status.FAILED, null, "Empty audio payload.", attemptedAt);
        }
        try {
            byte[] multipart = buildMultipart(audioBytes, languageCode);
            ProviderHttpClient.ProviderResponse response = http.send(
                "POST", SARVAM_STT_URL,
                "multipart/form-data; boundary=" + BOUNDARY,
                "Bearer " + apiKey, // Sarvam expects "Authorization: Bearer <api-key>"
                multipart);
            if (response.statusCode == 401 || response.statusCode == 403) {
                return new TranscriptionResult(Status.INVALID_CREDENTIALS, null,
                    "Sarvam rejected the credentials (HTTP " + response.statusCode + ").", attemptedAt);
            }
            if (response.statusCode < 200 || response.statusCode >= 300) {
                return new TranscriptionResult(Status.FAILED, null,
                    "Sarvam returned HTTP " + response.statusCode + ".", attemptedAt);
            }
            JsonNode json = mapper.readTree(response.body);
            String transcript = json.path("transcript").asText("").trim();
            if (transcript.isEmpty()) {
                return new TranscriptionResult(Status.FAILED, null,
                    "Sarvam returned no transcript for this audio.", attemptedAt);
            }
            return new TranscriptionResult(Status.TRANSCRIBED, transcript, "sarvam:stt", attemptedAt);
        } catch (Exception e) {
            log.warn("Sarvam transcription failed: {}", e.getMessage());
            return new TranscriptionResult(Status.FAILED, null,
                "Transcription request failed (network/timeout).", attemptedAt);
        }
    }

    private byte[] buildMultipart(byte[] audio, String languageCode) {
        String lang = (languageCode == null || languageCode.isBlank()) ? "unknown" : languageCode.trim();
        String preamble =
            "--" + BOUNDARY + "\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"note.m4a\"\r\n" +
            "Content-Type: audio/mp4\r\n\r\n";
            String mid = "\r\n--" + BOUNDARY + "\r\n" +
            "Content-Disposition: form-data; name=\"model\"\r\n\r\n" + sttModel + "\r\n" +
            "--" + BOUNDARY + "\r\n" +
            "Content-Disposition: form-data; name=\"language_code\"\r\n\r\n" + lang + "\r\n" +
            "--" + BOUNDARY + "--\r\n";
        byte[] pre = preamble.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] post = mid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[pre.length + audio.length + post.length];
        System.arraycopy(pre, 0, out, 0, pre.length);
        System.arraycopy(audio, 0, out, pre.length, audio.length);
        System.arraycopy(post, 0, out, pre.length + audio.length, post.length);
        return out;
    }
}
