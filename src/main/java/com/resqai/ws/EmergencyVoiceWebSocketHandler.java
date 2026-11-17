package com.resqai.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resqai.model.EmergencyVoiceSessionDocument;
import com.resqai.model.VoiceConversationHistoryDocument;
import com.resqai.repository.EmergencyVoiceSessionRepository;
import com.resqai.repository.VoiceConversationHistoryRepository;
import com.resqai.service.EmergencyConversationService;
import com.resqai.service.EmergencySituationReportService;
import com.resqai.service.FirstAidOrchestrator;
import com.resqai.service.SarvamStreamingSttService;
import com.resqai.service.SarvamStreamingTtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real-time voice bridge for the browser-based AI first-aid conversation
 * (spec PART 4-7): the bystander speaks in the app; PCM audio streams over
 * this WebSocket to Sarvam streaming STT; the deterministic conversation
 * engine decides the next response; Sarvam streaming TTS speaks it back as
 * audio chunks the browser plays immediately (barge-in via STT speech start).
 *
 * Twilio is NOT part of this conversation - it remains only for the
 * ambulance telephone integration (spec architecture decision, Option B).
 *
 * Protocol (all JSON text frames + raw PCM16 binary frames):
 *   client -> {"type":"start","language":"en","consent":true}
 *   client -> binary PCM16 16 kHz mono chunks (while not muted)
 *   client -> {"type":"end"}
 *   server -> {"type":"status","value":"LISTENING|PROCESSING|SPEAKING|RECONNECTING"}
 *   server -> {"type":"barge_in"}
 *   server -> {"type":"transcript","role":"bystander","text":...}
 *   server -> {"type":"ai","text":...,"audio":"<base64 mp3>","urgent":bool}
 *   server -> {"type":"followup","message":...}
 *   server -> {"type":"error","message":...}
 */
@Component
public class EmergencyVoiceWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EmergencyVoiceWebSocketHandler.class);
    private static final int SAMPLE_RATE = 16000;

    private final EmergencyVoiceSessionRepository sessionRepository;
    private final VoiceConversationHistoryRepository historyRepository;
    private final SarvamStreamingSttService sttService;
    private final SarvamStreamingTtsService ttsService;
    private final EmergencyConversationService conversation;
    private final EmergencySituationReportService sitrepService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Live WS contexts keyed by the WebSocket session id. */
    private final Map<String, VoiceContext> contexts = new ConcurrentHashMap<>();

    private static class VoiceContext {
        WebSocketSession ws;
        EmergencyVoiceSessionDocument session;
        SarvamStreamingSttService.Stream stt;
        SarvamStreamingTtsService.Stream tts;
        /** Buffers the current utterance while streaming to STT. */
        java.io.ByteArrayOutputStream pcmBuffer = new java.io.ByteArrayOutputStream();
        long speechStartMs = 0;
        boolean inUtterance = false;
        boolean muted = false;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) {
        // Token validation happens on the first "start" message (the session
        // token arrives as a query parameter but is checked against Mongo).
        sendJson(ws, Map.of("type", "status", "value", "CONNECTED"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        VoiceContext ctx = contexts.get(ws.getId());
        Map<String, Object> msg;
        try {
            msg = mapper.readValue(message.getPayload(), Map.class);
        } catch (Exception e) {
            sendJson(ws, Map.of("type", "error", "message", "malformed control message"));
            return;
        }
        String type = String.valueOf(msg.get("type"));

        if ("start".equals(type)) {
            handleStart(ws, msg);
            return;
        }
        if (ctx == null || ctx.session == null) {
            sendJson(ws, Map.of("type", "error", "message", "session not started"));
            return;
        }
        if ("mute".equals(type)) {
            ctx.muted = Boolean.TRUE.equals(msg.get("muted"));
            ctx.session.setMicMuted(ctx.muted);
            sessionRepository.save(ctx.session);
            return;
        }
        if ("end".equals(type)) {
            endSession(ctx, "BYSTANDER");
            try { ws.close(CloseStatus.NORMAL); } catch (Exception ignore) {}
            return;
        }
        sendJson(ws, Map.of("type", "error", "message", "unknown message type"));
    }

    private void handleStart(WebSocketSession ws, Map<String, Object> msg) {
        String token = String.valueOf(msg.get("token"));
        String language = String.valueOf(msg.getOrDefault("language", "en"));
        boolean consent = Boolean.TRUE.equals(msg.get("consent"));

        var sessionOpt = sessionRepository.findBySessionToken(token);
        if (sessionOpt.isEmpty()) {
            sendJson(ws, Map.of("type", "error", "message", "invalid session token"));
            try { ws.close(CloseStatus.NOT_ACCEPTABLE); } catch (Exception ignore) {}
            return;
        }
        var session = sessionOpt.get();
        if (!consent) {
            sendJson(ws, Map.of("type", "error", "message",
                "microphone consent is required before the conversation starts"));
            return;
        }
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(Instant.now())) {
            sendJson(ws, Map.of("type", "error", "message", "session token expired - request a new session"));
            return;
        }

        VoiceContext ctx = new VoiceContext();
        ctx.ws = ws;
        ctx.session = session;
        session.setLanguage(language);
        session.setConsentAt(Instant.now());
        session.setState("ACTIVE");
        session.setLastActivityAt(Instant.now());
        sessionRepository.save(session);
        contexts.put(ws.getId(), ctx);

        // Sarvam streaming STT: final transcripts drive the conversation.
        ctx.stt = sttService.open(language, new SarvamStreamingSttService.Listener() {
            @Override public void onPartial(String text) {
                sendJson(ws, Map.of("type", "partial", "role", "bystander", "text", text));
            }
            @Override public void onFinal(String text, String detectedLanguage) {
                sendJson(ws, Map.of("type", "transcript", "role", "bystander", "text", text));
                handleUtterance(ctx, text);
            }
            @Override public void onSpeechStart() {
                // Barge-in: the browser stops TTS playback the moment the
                // bystander starts speaking.
                sendJson(ws, Map.of("type", "barge_in"));
                sendJson(ws, Map.of("type", "status", "value", "LISTENING"));
            }
            @Override public void onSpeechEnd() { }
            @Override public void onReady() {
                sendJson(ws, Map.of("type", "status", "value", "LISTENING"));
            }
            @Override public void onFatalError(String message) {
                sendJson(ws, Map.of("type", "error", "message",
                    "Speech recognition is unavailable: " + message));
            }
            @Override public void onTransientError(String message) {
                sendJson(ws, Map.of("type", "status", "value", "RECONNECTING"));
            }
        });

        // Intro + first question spoken through TTS.
        String intro = conversation.intro(language);
        logHistory(session, "AGENT", intro);
        speak(ctx, intro, false);
        sendJson(ws, Map.of(
            "type", "session",
            "sessionId", session.getSessionId(),
            "patientName", session.getVictimName() == null ? "" : session.getVictimName(),
            "emergencyId", session.getVictimEmergencyId() == null ? "" : session.getVictimEmergencyId(),
            "language", language));
        log.info("voice session started: {}", session.getSessionId());
    }

    private void handleUtterance(VoiceContext ctx, String transcript) {
        var session = ctx.session;
        session.setLastActivityAt(Instant.now());
        sendJson(ctx.ws, Map.of("type", "status", "value", "PROCESSING"));

        // The AI conversation must not run on the WS listener thread.
        Thread.ofVirtual().name("resqai-agent-turn").start(() -> {
            try {
                var reportOpt = sessionRepository.findById(session.getId()) == null
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(session);
                String category = reportCategory(session);
                FirstAidOrchestrator.MedicalContext medical = medicalContext(session);
                // Deliver any scheduler-queued follow-up first (20-min
                // follow-up while the session stays ACTIVE).
                if (session.getPendingAgentMessage() != null
                    && !session.getPendingAgentMessage().isBlank()) {
                    String pendingMsg = session.getPendingAgentMessage();
                    session.setPendingAgentMessage(null);
                    sessionRepository.save(session);
                    logHistory(session, "AGENT", pendingMsg);
                    speak(ctx, pendingMsg, false);
                }
                EmergencyConversationService.AgentTurn turn =
                    conversation.handleUtterance(session, category, medical, transcript);

                sitrepService.recordTimeline(session.getIncidentId(),
                    "BYSTANDER_REPORTED", turn.timelineEntry, turn.urgent);
                // Serious deterioration: immediate URGENT situation report
                // through the Twilio channel (never waits for the 5-min job).
                if (turn.urgent) {
                    var urgent = sitrepService.generate(session, "URGENT", true);
                    sitrepService.relay(urgent, sitrepService.ambulanceDestination());
                }
                logHistory(session, "AGENT", turn.speech);
                speak(ctx, turn.speech, turn.urgent);
            } catch (Exception e) {
                log.error("agent turn failed: {}", e.getMessage());
                sendJson(ctx.ws, Map.of("type", "error",
                    "message", "The assistant could not process that. Please repeat."));
            }
        });
    }

    private void speak(VoiceContext ctx, String text, boolean urgent) {
        sendJson(ctx.ws, Map.of("type", "status", "value", "SPEAKING"));
        if (ctx.tts != null) { try { ctx.tts.close(); } catch (Exception ignore) {} }
        ctx.tts = ttsService.open(ctx.session.getLanguage(),
            new SarvamStreamingTtsService.Listener() {
                @Override public void onAudioChunk(String base64Audio, String contentType) {
                    sendJson(ctx.ws, Map.of(
                        "type", "ai_audio",
                        "audio", base64Audio,
                        "contentType", contentType,
                        "urgent", urgent));
                }
                @Override public void onUtteranceComplete() {
                    sendJson(ctx.ws, Map.of("type", "status", "value", "LISTENING"));
                }
                @Override public void onError(String message) {
                    sendJson(ctx.ws, Map.of("type", "error", "message",
                        "Speech playback failed: " + message));
                }
            });
        ctx.tts.speak(text);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
        VoiceContext ctx = contexts.get(ws.getId());
        if (ctx == null || ctx.stt == null || ctx.muted) return;
        byte[] pcm = message.getPayload().array();
        ctx.stt.sendAudio(pcm);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        VoiceContext ctx = contexts.remove(ws.getId());
        if (ctx == null) return;
        if (ctx.stt != null) try { ctx.stt.close(); } catch (Exception ignore) {}
        if (ctx.tts != null) try { ctx.tts.close(); } catch (Exception ignore) {}
        var session = ctx.session;
        if (session != null && "ACTIVE".equals(session.getState())) {
            // Honest DISCONNECTED state: incident persists; the bystander
            // may reconnect (browser refresh / network loss) - spec PART 9.
            session.setState("DISCONNECTED");
            session.setLastActivityAt(Instant.now());
            sessionRepository.save(session);
            logHistory(session, "SYSTEM", "Browser disconnected; session kept for reconnection");
        }
    }

    private void endSession(VoiceContext ctx, String reason) {
        var session = ctx.session;
        session.setState("ENDED");
        session.setEndReason(reason);
        session.setEndedAt(Instant.now());
        sessionRepository.save(session);
        logHistory(session, "SYSTEM", "Session ended by bystander (" + reason + ")");
        if (ctx.stt != null) try { ctx.stt.close(); } catch (Exception ignore) {}
        if (ctx.tts != null) try { ctx.tts.close(); } catch (Exception ignore) {}
    }

    private void sendJson(WebSocketSession ws, Map<?, ?> payload) {
        try {
            if (ws.isOpen()) {
                synchronized (ws) {
                    ws.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
                }
            }
        } catch (Exception e) {
            log.debug("ws send failed: {}", e.getMessage());
        }
    }

    private void logHistory(EmergencyVoiceSessionDocument session, String role, String message) {
        try {
            historyRepository.save(new VoiceConversationHistoryDocument(
                session.getSessionId(), session.getIncidentId(), role, message));
        } catch (Exception e) {
            log.warn("history write failed: {}", e.getMessage());
        }
    }

    private String reportCategory(EmergencyVoiceSessionDocument session) {
        return session.getReportId() == null ? "OTHER"
            : java.util.Optional.ofNullable(
                reports.findByReportId(session.getReportId()).orElse(null))
            .map(r -> r.getCategory()).orElse("OTHER");
    }

    private FirstAidOrchestrator.MedicalContext medicalContext(EmergencyVoiceSessionDocument session) {
        return users.findByEmergencyCode(session.getEmergencyCode())
            .filter(u -> u.isEnabled())
            .map(v -> profiles.findByUserId(v.getId()).map(pr ->
                new FirstAidOrchestrator.MedicalContext(
                    pr.getBloodGroup(),
                    pr.getAllergies() == null ? null : String.join(", ", pr.getAllergies()),
                    pr.getMedicalConditions() == null ? java.util.List.of() : pr.getMedicalConditions(),
                    pr.getCurrentMedications() == null ? java.util.List.of() : pr.getCurrentMedications()))
                .orElse(new FirstAidOrchestrator.MedicalContext(null, null, java.util.List.of(), java.util.List.of())))
            .orElse(new FirstAidOrchestrator.MedicalContext(null, null, java.util.List.of(), java.util.List.of()));
    }

    private final com.resqai.repository.BystanderReportRepository reports;
    private final com.resqai.repository.UserRepository users;
    private final com.resqai.repository.EmergencyProfileRepository profiles;

    public EmergencyVoiceWebSocketHandler(
            EmergencyVoiceSessionRepository sessionRepository,
            VoiceConversationHistoryRepository historyRepository,
            SarvamStreamingSttService sttService,
            SarvamStreamingTtsService ttsService,
            EmergencyConversationService conversation,
            EmergencySituationReportService sitrepService,
            com.resqai.repository.BystanderReportRepository reports,
            com.resqai.repository.UserRepository users,
            com.resqai.repository.EmergencyProfileRepository profiles) {
        this.sessionRepository = sessionRepository;
        this.historyRepository = historyRepository;
        this.sttService = sttService;
        this.ttsService = ttsService;
        this.conversation = conversation;
        this.sitrepService = sitrepService;
        this.reports = reports;
        this.users = users;
        this.profiles = profiles;
    }
}
