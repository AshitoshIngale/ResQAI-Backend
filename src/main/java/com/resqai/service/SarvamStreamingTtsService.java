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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Streaming text-to-speech for the emergency voice assistant, using Sarvam's
 * TTS WebSocket API (bulbul family).
 *
 * Protocol (verified against the Sarvam AsyncAPI spec, 2026-11):
 *   connect  wss://api.sarvam.ai/text-to-speech/ws?model=bulbul:v3
 *   auth     header: Api-Subscription-Key
 *   client   FIRST {"type":"config","data":{model, language_code, speaker,
 *                  speech_sample_rate, output_audio_codec, pace, ...}}
 *            then  {"type":"text","data":{"text":...}}
 *            then  {"type":"flush"} to force the buffer through.
 *   server   {"type":"audio","data":{"content_type":"audio/mp3","audio":"<b64>"}}
 *            {"type":"event","data":{"event_type":"final"}}
 *            {"type":"error","data":{"message":...}}
 *
 * Chunks are forwarded to the browser as they arrive (progressive playback
 * via MediaSource); the completion event triggers tts.done upstream.
 *
 * The connection auto-closes after one minute of inactivity, so an idle
 * connection is pinged. A failed synthesis falls back upstream (honest
 * error to the UI, never silence presented as speech).
 */
@Service
public class SarvamStreamingTtsService {

    private static final Logger log = LoggerFactory.getLogger(SarvamStreamingTtsService.class);
    private static final String WSS_URL = "wss://api.sarvam.ai/text-to-speech/ws";
    private static final long PING_INTERVAL_MS = 25_000;

    private final String apiKey;
    private final String model;
    private final String speaker;
    private final String sampleRate;
    private final String bitrate;
    private final double pace;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public SarvamStreamingTtsService(
            @Value("${resqai.sarvam.api-key:}") String apiKey,
            @Value("${resqai.sarvam.tts-stream-model:bulbul:v3}") String model,
            @Value("${resqai.sarvam.tts-speaker:anand}") String speaker,
            @Value("${resqai.sarvam.tts-sample-rate:22050}") String sampleRate,
            @Value("${resqai.sarvam.tts-bitrate:96k}") String bitrate,
            @Value("${resqai.sarvam.tts-pace:0.9}") double pace) {
        this.apiKey = apiKey;
        this.model = model;
        this.speaker = speaker;
        this.sampleRate = sampleRate;
        this.bitrate = bitrate;
        this.pace = pace;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public interface Listener {
        /** One base64 MP3 chunk, forwarded to the browser immediately. */
        void onAudioChunk(String base64Audio, String contentType);
        void onUtteranceComplete();
        void onError(String message);
    }

    /**
     * One TTS stream for a session. Configuration is sent on (re)connect;
     * text is queued and spoken in order. One utterance at a time.
     */
    public final class Stream implements WebSocket.Listener {

        private final String languageCode;
        private final Listener listener;
        private final AtomicReference<WebSocket> socket = new AtomicReference<>();
        private final AtomicBoolean closedByUs = new AtomicBoolean(false);
        private final StringBuilder textBuffer = new StringBuilder();
        private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        private volatile boolean configSent;
        private Thread watchdog;
        /** Text queued while the connection/config completes (never lost). */
        private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingText =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

        Stream(String languageCode, Listener listener) {
            this.languageCode = languageCode;
            this.listener = listener;
            connect();
            this.watchdog = Thread.ofPlatform().name("resqai-tts-ping").daemon().start(() -> {
                while (!closedByUs.get()) {
                    try {
                        Thread.sleep(PING_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    WebSocket ws = socket.get();
                    if (ws != null && System.currentTimeMillis() - lastActivity.get() > PING_INTERVAL_MS) {
                        try {
                            ws.sendText("{\"type\":\"ping\"}", true);
                            lastActivity.set(System.currentTimeMillis());
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
                    + "&send_completion_event=true");
                httpClient.newWebSocketBuilder()
                    .header("Api-Subscription-Key", apiKey)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri, this)
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            listener.onError("TTS connect failed: " + err.getMessage());
                        } else {
                            socket.set(ws);
                            sendConfig(ws);
                        }
                    });
            } catch (Exception e) {
                listener.onError("TTS connect error: " + e.getMessage());
            }
        }

        private void sendConfig(WebSocket ws) {
            try {
                ObjectNode config = mapper.createObjectNode();
                config.put("type", "config");
                ObjectNode data = config.putObject("data");
                data.put("model", model);
                data.put("language_code", languageCode);
                data.put("speaker", speaker);
                data.put("speech_sample_rate", sampleRate);
                data.put("output_audio_codec", "mp3");
                data.put("output_audio_bitrate", bitrate);
                data.put("pace", pace);
                data.put("min_buffer_size", 30);
                data.put("max_chunk_length", 120);
                ws.sendText(mapper.writeValueAsString(config), true);
                configSent = true;
                // Drain anything queued while the connection was opening.
                String queued;
                while ((queued = pendingText.poll()) != null) {
                    sendNow(queued);
                }
            } catch (Exception e) {
                listener.onError("TTS config failed: " + e.getMessage());
            }
        }

        private void sendNow(String clean) {
            try {
                ObjectNode msg = mapper.createObjectNode();
                msg.put("type", "text");
                msg.putObject("data").put("text", clean);
                WebSocket ws = socket.get();
                if (ws == null) return;
                ws.sendText(mapper.writeValueAsString(msg), true);
                ObjectNode flush = mapper.createObjectNode();
                flush.put("type", "flush");
                ws.sendText(mapper.writeValueAsString(flush), true);
                lastActivity.set(System.currentTimeMillis());
            } catch (Exception e) {
                listener.onError("TTS send failed: " + e.getMessage());
            }
        }

        /** Synthesizes one utterance (whole reply text, max ~2500 chars).
         *  If the connection/config is not ready yet, the text is QUEUED and
         *  sent the moment the stream is ready - early speak() calls after
         *  open() must never be lost (that made the AI go silent). */
        public void speak(String text) {
            String clean = text == null ? "" : text.trim();
            if (clean.isEmpty()) return;
            if (clean.length() > 2500) clean = clean.substring(0, 2500);
            WebSocket ws = socket.get();
            if (ws == null || !configSent) {
                pendingText.offer(clean);
                return;
            }
            sendNow(clean);
        }

        /** Interrupts the current utterance (barge-in): buffer is abandoned. */
        public void cancel() {
            // Sarvam has no mid-utterance cancel signal; upstream stops
            // forwarding chunks after this point (the voice handler drops
            // audio until the completion/error event arrives or the next
            // utterance begins). Close+reopen guarantees a clean buffer.
            if (closedByUs.get()) return;
            WebSocket ws = socket.get();
            if (ws != null) {
                try { ws.abort(); } catch (Exception ignore) { }
                socket.set(null);
            }
            configSent = false;
            connect();
        }

        public void close() {
            closedByUs.set(true);
            if (watchdog != null) watchdog.interrupt();
            WebSocket ws = socket.get();
            if (ws != null) {
                try { ws.abort(); } catch (Exception ignore) { }
            }
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
            configSent = false;
            if (!closedByUs.get()) {
                // Idle timeouts are normal; only surface if a synthesis was pending.
                listener.onError("TTS closed (" + statusCode + ")");
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            socket.set(null);
            configSent = false;
            if (!closedByUs.get()) listener.onError("TTS error: " + error.getMessage());
        }

        private void handleServerEvent(String json) {
            try {
                JsonNode node = mapper.readTree(json);
                String type = node.path("type").asText("");
                if ("audio".equals(type)) {
                    JsonNode data = node.path("data");
                    listener.onAudioChunk(data.path("audio").asText(""),
                        data.path("content_type").asText("audio/mp3"));
                } else if ("event".equals(type)) {
                    if ("final".equals(node.path("data").path("event_type").asText())) {
                        listener.onUtteranceComplete();
                    }
                } else if ("error".equals(type)) {
                    listener.onError(node.path("data").path("message").asText("TTS error"));
                }
            } catch (Exception e) {
                log.debug("TTS event parse failed: {}", e.getMessage());
            }
        }
    }

    /** Opens a stream for one language (en-IN | hi-IN | mr-IN). */
    public Stream open(String languageCode, Listener listener) {
        return new Stream(SarvamStreamingSttService.normalizeLanguage(languageCode), listener);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
