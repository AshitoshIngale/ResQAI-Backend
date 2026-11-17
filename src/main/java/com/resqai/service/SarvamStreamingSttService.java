package com.resqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Streaming speech-to-text for one emergency voice session, using Sarvam's
 * realtime WebSocket API (saaras:v3-realtime / saaras:v4).
 *
 * Protocol (verified against the Sarvam AsyncAPI spec, 2026-11):
 *   connect  wss://api.sarvam.ai/speech-to-text-realtime/ws
 *            ?model=...&language_code=...&encoding=linear16&sample_rate=16000
 *            &endpointing=vad&stream_type=...&threshold=...&silence_duration_ms=...
 *   auth     header: Api-Subscription-Key
 *   client   {"event":"audio_input","audio":"<base64 pcm16>"}
 *            {"event":"ping"}
 *            {"event":"end"}
 *   server   {"event":"session.begin",...}
 *            {"event":"vad.speech_start","utterance_idx":n}
 *            {"event":"transcript.partial","utterance_idx":n,"text":...}
 *            {"event":"transcript.final","utterance_idx":n,"text":...}
 *            {"event":"error","code":...,"is_fatal":bool,"message":...}
 *
 * VAD speech_start events are the barge-in signal: the browser pauses TTS
 * playback the moment the bystander starts speaking.
 *
 * Reliability: reconnects with exponential backoff while the browser session
 * is active; a transient Sarvam outage NEVER terminates the emergency
 * conversation (the UI shows "Reconnecting" honestly).
 */
@Service
public class SarvamStreamingSttService {

    private static final Logger log = LoggerFactory.getLogger(SarvamStreamingSttService.class);
    private static final String WSS_URL = "wss://api.sarvam.ai/speech-to-text-realtime/ws";
    private static final long PING_INTERVAL_MS = 20_000;

    private final String apiKey;
    private final String model;
    private final String streamType;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public SarvamStreamingSttService(
            @Value("${resqai.sarvam.api-key:}") String apiKey,
            @Value("${resqai.sarvam.stt-realtime-model:saaras:v3-realtime}") String model,
            @Value("${resqai.sarvam.stt-stream-type:balanced}") String streamType) {
        this.apiKey = apiKey;
        this.model = model;
        this.streamType = streamType;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Callback surface for one live stream. */
    public interface Listener {
        void onPartial(String text);
        void onFinal(String text, String language);
        void onSpeechStart();
        void onSpeechEnd();
        void onReady();
        void onFatalError(String message);
        void onTransientError(String message);
    }

    /**
     * One live realtime STT stream for a session. Owns its WebSocket and
     * reconnects transparently while {@link #close()} has not been called.
     */
    public final class Stream implements WebSocket.Listener {

        private final String languageCode;
        private final Listener listener;
        private final AtomicReference<WebSocket> socket = new AtomicReference<>();
        private final AtomicBoolean closedByUs = new AtomicBoolean(false);
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final StringBuilder textBuffer = new StringBuilder();
        private final AtomicLong lastPing = new AtomicLong(System.currentTimeMillis());
        private volatile int reconnectAttempt;
        private Thread watchdog;

        Stream(String languageCode, Listener listener) {
            this.languageCode = languageCode;
            this.listener = listener;
            connect();
            this.watchdog = Thread.ofPlatform().name("resqai-stt-ping").daemon().start(() -> {
                while (!closedByUs.get()) {
                    try {
                        Thread.sleep(PING_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    WebSocket ws = socket.get();
                    if (ws != null && System.currentTimeMillis() - lastPing.get() > PING_INTERVAL_MS) {
                        try {
                            ws.sendText("{\"event\":\"ping\"}", true);
                        } catch (Exception ignore) { /* reconnect handles it */ }
                    }
                }
            });
        }

        private void connect() {
            if (closedByUs.get()) return;
            try {
                URI uri = URI.create(WSS_URL
                    + "?model=" + urlEncode(model)
                    + "&language_code=" + urlEncode(languageCode)
                    + "&encoding=linear16&sample_rate=16000"
                    + "&endpointing=vad"
                    + "&stream_type=" + urlEncode(streamType));
                httpClient.newWebSocketBuilder()
                    .header("Api-Subscription-Key", apiKey)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri, this)
                    .whenComplete((ws, err) -> {
                        if (err != null) scheduleReconnect("connect failed: " + err.getMessage());
                        else socket.set(ws);
                    });
            } catch (Exception e) {
                scheduleReconnect("connect error: " + e.getMessage());
            }
        }

        private void scheduleReconnect(String reason) {
            if (closedByUs.get()) return;
            ready.set(false);
            listener.onTransientError(reason);
            long delayMs = Math.min(30_000, 1000L * (1L << Math.min(4, reconnectAttempt++)));
            log.warn("STT stream reconnecting in {} ms ({})", delayMs, reason);
            Thread.ofPlatform().daemon().start(() -> {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { return; }
                if (!closedByUs.get()) connect();
            });
        }

        /** Sends one PCM16 16 kHz mono chunk (raw bytes) for transcription. */
        public void sendAudio(byte[] pcm16) {
            WebSocket ws = socket.get();
            if (ws == null || closedByUs.get()) return;
            try {
                ObjectNode msg = mapper.createObjectNode();
                msg.put("event", "audio_input");
                msg.put("audio", Base64.getEncoder().encodeToString(pcm16));
                ws.sendText(mapper.writeValueAsString(msg), true);
                lastPing.set(System.currentTimeMillis());
            } catch (Exception e) {
                listener.onTransientError("send failed: " + e.getMessage());
            }
        }

        /** Gracefully ends the Sarvam session before closing. */
        public void close() {
            closedByUs.set(true);
            if (watchdog != null) watchdog.interrupt();
            WebSocket ws = socket.get();
            if (ws != null) {
                try { ws.sendText("{\"event\":\"end\"}", true); } catch (Exception ignore) { }
                try { ws.abort(); } catch (Exception ignore) { }
            }
        }

        // ---- WebSocket.Listener -------------------------------------------------

        @Override
        public void onOpen(WebSocket ws) {
            reconnectAttempt = 0;
            log.info("STT stream connected");
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String full = textBuffer.toString();
                textBuffer.setLength(0);
                handleServerEvent(full);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            socket.set(null);
            if (!closedByUs.get()) {
                // 1003 = quota/rate/invalid key (fatal), 4000 = bad config.
                if (statusCode == 1003 || statusCode == 4000) {
                    closedByUs.set(true);
                    listener.onFatalError("STT rejected (code " + statusCode + ")");
                } else {
                    scheduleReconnect("closed: " + statusCode + " " + reason);
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            socket.set(null);
            if (!closedByUs.get()) scheduleReconnect("error: " + error.getMessage());
        }

        private void handleServerEvent(String json) {
            try {
                JsonNode node = mapper.readTree(json);
                String event = node.path("event").asText("");
                switch (event) {
                    case "session.begin" -> {
                        ready.set(true);
                        listener.onReady();
                    }
                    case "vad.speech_start" -> listener.onSpeechStart();
                    case "vad.speech_end" -> listener.onSpeechEnd();
                    case "transcript.partial" ->
                        listener.onPartial(node.path("text").asText(""));
                    case "transcript.final" -> listener.onFinal(
                        node.path("text").asText(""),
                        node.path("language").asText(null));
                    case "error" -> {
                        boolean fatal = node.path("is_fatal").asBoolean(false);
                        String message = node.path("message").asText("unknown");
                        if (fatal) {
                            closedByUs.set(true);
                            listener.onFatalError(message);
                        } else {
                            listener.onTransientError(message);
                        }
                    }
                    default -> { /* pong, session.end, config.updated */ }
                }
            } catch (Exception e) {
                log.debug("STT event parse failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Opens a stream. Supported languages for this emergency assistant:
     * en-IN, hi-IN, mr-IN (chosen by the bystander, never inferred from GPS).
     */
    public Stream open(String languageCode, Listener listener) {
        return new Stream(normalizeLanguage(languageCode), listener);
    }

    static String normalizeLanguage(String language) {
        return switch (language == null ? "en" : language.toLowerCase()) {
            case "hi", "hi-IN" -> "hi-IN";
            case "mr", "mr-IN" -> "mr-IN";
            default -> "en-IN";
        };
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
