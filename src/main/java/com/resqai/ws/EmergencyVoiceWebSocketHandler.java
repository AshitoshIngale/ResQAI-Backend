package com.resqai.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resqai.model.EmergencyVoiceSessionDocument;
import com.resqai.model.VoiceConversationHistoryDocument;
import com.resqai.repository.EmergencyVoiceSessionRepository;
import com.resqai.repository.VoiceConversationHistoryRepository;
import com.resqai.service.AudioWavUtil;
import com.resqai.service.EmergencyConversationService;
import com.resqai.service.EmergencyFirstAidService;
import com.resqai.service.EmergencySituationReportService;
import com.resqai.service.FirstAidOrchestrator;
import com.resqai.service.GeminiFirstAidService;
import com.resqai.service.GeminiTtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real-time voice bridge for the browser/native AI first-aid conversation
 * (spec PART 7, architecture Option B - native variant): the bystander speaks
 * in the app; PCM16 16 kHz mono audio streams over this WebSocket; the server
 * buffers utterances with silence detection and sends each utterance STRAIGHT
 * to the user's Gemini API (transcribes AND writes the first-aid response in
 * one call - Sarvam was removed entirely per user decision). The reply is
 * spoken by Gemini TTS with natural style direction; if Google is unreachable
 * the text goes to the app's offline TTS so the reply is NEVER lost.
 *
 * Twilio is NOT part of this conversation - it remains only for the
 * ambulance telephone integration (spec architecture decision, Option B).
 *
 * Protocol (JSON text frames + raw PCM16 binary frames):
 *   client -> {"type":"start","token":...,"language":"en","consent":true}
 *   client -> binary PCM16 16 kHz mono chunks (while not muted)
 *   client -> {"type":"end"}
 *   server -> {"type":"status","value":"LISTENING|PROCESSING|SPEAKING|RECONNECTING"}
 *   server -> {"type":"transcript","role":"bystander","text":...}
 *   server -> {"type":"ai_audio","audio":"<base64 wav>","contentType":"audio/wav"}
 *   server -> {"type":"ai_text","text":...}   (offline TTS fallback)
 *   server -> {"type":"session","patientName":...,"emergencyId":...}
 *   server -> {"type":"error","message":...}
 */
@Component
public class EmergencyVoiceWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EmergencyVoiceWebSocketHandler.class);
    private static final int SAMPLE_RATE = 16000;
    /** 100 ms of PCM16 mono at 16 kHz. */
    private static final int FRAME_MS = 100;
    /** RMS above this counts as speech (16-bit PCM). */
    private static final double SPEECH_RMS = 500.0;
    /** Utterance ends after this many consecutive quiet frames (1.2 s). */
    private static final int QUIET_FRAMES_TO_END = 12;
    /** Minimum speech frames before an utterance is accepted (0.8 s). */
    private static final int MIN_SPEECH_FRAMES = 8;
    /** Hard cap: 20 s per utterance. */
    private static final int MAX_SPEECH_FRAMES = 200;

    private final EmergencyVoiceSessionRepository sessionRepository;
    private final VoiceConversationHistoryRepository historyRepository;
    private final EmergencyConversationService conversation;
    private final GeminiFirstAidService gemini;
    private final GeminiTtsService geminiTts;
    private final EmergencySituationReportService sitrepService;
    private final com.resqai.repository.BystanderReportRepository reports;
    private final com.resqai.repository.UserRepository users;
    private final com.resqai.repository.EmergencyProfileRepository profiles;
    private final EmergencyFirstAidService firstAidService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Live WS contexts keyed by the WebSocket session id. */
    private final Map<String, VoiceContext> contexts = new ConcurrentHashMap<>();

    public EmergencyVoiceWebSocketHandler(
            EmergencyVoiceSessionRepository sessionRepository,
            VoiceConversationHistoryRepository historyRepository,
            EmergencyConversationService conversation,
            GeminiFirstAidService gemini,
            GeminiTtsService geminiTts,
            EmergencySituationReportService sitrepService,
            com.resqai.repository.BystanderReportRepository reports,
            com.resqai.repository.UserRepository users,
            com.resqai.repository.EmergencyProfileRepository profiles,
            EmergencyFirstAidService firstAidService) {
        this.firstAidService = firstAidService;
        this.sessionRepository = sessionRepository;
        this.historyRepository = historyRepository;
        this.conversation = conversation;
        this.gemini = gemini;
        this.geminiTts = geminiTts;
        this.sitrepService = sitrepService;
        this.reports = reports;
        this.users = users;
        this.profiles = profiles;
    }

    private static class VoiceContext {
        WebSocketSession ws;
        EmergencyVoiceSessionDocument session;
        ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
        boolean inUtterance = false;
        int speechFrames = 0;
        int quietFrames = 0;
        boolean muted = false;
        /** Adaptive noise floor: tracks the quietest recent frames so the
         *  speech threshold works on ANY microphone. Starts conservative. */
        double noiseFloor = 120.0;
        double lastSpeechRms = 0;
    }

    /** Speech threshold for THIS connection: well above its noise floor,
     *  but never so high that normal speech is missed. */
    private double speechThreshold(VoiceContext ctx) {
        return Math.max(180.0, ctx.noiseFloor * 3.0);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) {
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
        // All sends (status, transcripts, AI audio) happen from MULTIPLE
        // threads (agent turn, TTS synthesis). Raw sessions are
        // single-threaded - concurrent sends kill the connection. The
        // decorator serializes them. Gemini TTS delivers ONE complete WAV
        // per reply (a 15 s answer is ~1 MB base64), so the overflow buffer
        // must hold a full spoken reply - 512 KB closed the socket mid-reply
        // with 1006.
        ctx.ws = new ConcurrentWebSocketSessionDecorator(ws, 60000, 4 * 1024 * 1024);
        ctx.session = session;
        session.setLanguage(language);
        session.setConsentAt(Instant.now());
        session.setState("ACTIVE");
        session.setLastActivityAt(Instant.now());
        sessionRepository.save(session);
        contexts.put(ws.getId(), ctx);

        String intro = conversation.intro(language);
        logHistory(session, "AGENT", intro);
        sendJson(ws, Map.of(
            "type", "session",
            "sessionId", session.getSessionId(),
            "patientName", session.getVictimName() == null ? "" : session.getVictimName(),
            "emergencyId", session.getVictimEmergencyId() == null ? "" : session.getVictimEmergencyId(),
            "language", language));
        // Gemini TTS synthesis takes seconds; the WS container thread must
        // return immediately or the client's first binary frames queue up.
        Thread.ofVirtual().name("resqai-voice-intro").start(() -> speak(ctx, intro, false));
        sendJson(ws, Map.of("type", "status", "value", "LISTENING"));
        log.info("voice session started: {}", session.getSessionId());
    }

    /** Binary PCM frame: buffer it; utterances are finalized on silence. */
    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
        VoiceContext ctx = contexts.get(ws.getId());
        if (ctx == null || ctx.muted || ctx.session == null) return;
        byte[] pcm = new byte[message.getPayload().remaining()];
        message.getPayload().get(pcm);

        double rms = rms(pcm);
        double threshold = speechThreshold(ctx);
        boolean isSpeech = rms > threshold;

        if (!ctx.inUtterance) {
            // Track the ambient noise floor while quiet (fast down, slow up).
            if (rms < ctx.noiseFloor) {
                ctx.noiseFloor = ctx.noiseFloor * 0.7 + rms * 0.3;
            } else {
                ctx.noiseFloor = ctx.noiseFloor * 0.995 + rms * 0.005;
            }
            if (isSpeech) {
                ctx.inUtterance = true;
                ctx.speechFrames = 1;
                ctx.quietFrames = 0;
                ctx.lastSpeechRms = rms;
                ctx.pcmBuffer.reset();
                ctx.pcmBuffer.write(pcm, 0, pcm.length);
                sendJson(ctx.ws, Map.of("type", "status", "value", "PROCESSING"));
            }
            return;
        }

        ctx.pcmBuffer.write(pcm, 0, pcm.length);
        ctx.speechFrames++;
        if (isSpeech) { ctx.quietFrames = 0; ctx.lastSpeechRms = Math.max(ctx.lastSpeechRms, rms); }
        else ctx.quietFrames++;

        boolean longEnough = ctx.speechFrames >= MIN_SPEECH_FRAMES;
        boolean enoughQuiet = ctx.quietFrames >= QUIET_FRAMES_TO_END;
        boolean tooLong = ctx.speechFrames >= MAX_SPEECH_FRAMES;
        if ((longEnough && enoughQuiet) || tooLong) {
            ctx.inUtterance = false;
            byte[] utterance = ctx.pcmBuffer.toByteArray();
            double heardSeconds = ctx.speechFrames * 0.1;
            ctx.pcmBuffer.reset();
            ctx.speechFrames = 0;
            ctx.quietFrames = 0;
            finalizeUtterance(ctx, utterance, heardSeconds);
        }
    }

    /** STT + brain + TTS run off the WS thread; the socket stays responsive. */
    private void finalizeUtterance(VoiceContext ctx, byte[] utterancePcm, double heardSeconds) {
        var session = ctx.session;
        sendJson(ctx.ws, Map.of("type", "status", "value", "PROCESSING"));
        Thread.ofVirtual().name("resqai-voice-turn").start(() -> {
            try {
                byte[] wav = AudioWavUtil.pcm16ToWav(utterancePcm, SAMPLE_RATE, 1);
                var reportOpt = reports.findByReportId(session.getIncidentId());
                String category = reportOpt.map(r -> r.getCategory()).orElse("OTHER");
                FirstAidOrchestrator.MedicalContext medical = medicalContext(session.getEmergencyCode());

                String transcript = null;
                String reply = null;
                boolean urgent = false;

                // ONE BRAIN (Sarvam removed per user decision): the
                // bystander's voice goes STRAIGHT to Gemini - it transcribes
                // and writes the first-aid response in a single call.
                if (gemini.isConfigured()) {
                    var turn = gemini.audioTurn(session, category, medical, wav);
                    if (turn != null) {
                        transcript = turn.transcript();
                        reply = turn.reply();
                        urgent = turn.urgent();
                    }
                }

                if (transcript == null || transcript.isBlank()) {
                    sendJson(ctx.ws, Map.of("type", "diag", "message",
                        "Heard " + String.format("%.1f", heardSeconds)
                            + "s of audio but could not understand it - speak a "
                            + "little louder and closer to the phone."));
                    sendJson(ctx.ws, Map.of("type", "status", "value", "LISTENING"));
                    return;
                }
                sendJson(ctx.ws, Map.of("type", "transcript", "role", "bystander", "text", transcript));
                log.info("TURN step: transcript sent");
                logHistory(session, "BYSTANDER", transcript);
                conversation.updateFacts(session, transcript);
                log.info("TURN step: facts updated");
                boolean redFlag = firstAidService.isRedFlagUtterance(transcript);
                urgent = urgent || redFlag;
                sitrepService.recordTimeline(session.getIncidentId(),
                    "BYSTANDER_REPORTED", transcript, redFlag);
                log.info("TURN step: timeline recorded (urgent={})", urgent);

                if (reply == null || reply.isBlank()) {
                    // Deterministic fallback: reviewed KB guidance - never silence.
                    var turn = conversation.handleUtterance(session, category, medical, transcript);
                    reply = turn.speech;
                    urgent = urgent || turn.urgent;
                } else {
                    logHistory(session, "AGENT", reply);
                }
                log.info("TURN step: reply ready ({} chars, urgent={})",
                    reply == null ? -1 : reply.length(), urgent);

                if (urgent) {
                    var urgentReport = sitrepService.generate(session, "URGENT", true);
                    log.info("TURN step: urgent report generated");
                    sitrepService.relay(urgentReport, sitrepService.ambulanceDestination());
                    session.setUrgentFlagged(true);
                }

                log.info("TURN step: speaking now");
                speak(ctx, reply, urgent);
            } catch (Exception e) {
                log.error("voice turn failed: {}", e.getMessage());
                sendJson(ctx.ws, Map.of("type", "error",
                    "message", "The assistant could not process that. Please repeat."));
                sendJson(ctx.ws, Map.of("type", "status", "value", "LISTENING"));
            }
        });
    }

    /**
     * Speaks the reply with Gemini TTS (natural style direction + language
     * config inside the service). Runs on the turn thread - a Google
     * synthesis takes a few seconds, and the socket (concurrent decorator)
     * stays responsive for new audio meanwhile. If Google fails (rate limit,
     * outage) the TEXT goes to the app's offline TTS instead - the bystander
     * ALWAYS gets the reply, voice or text.
     */
    private void speak(VoiceContext ctx, String text, boolean urgent) {
        sendJson(ctx.ws, Map.of("type", "status", "value", "SPEAKING"));
        var session = ctx.session;
        String lang = session.getLanguage() == null ? "en" : session.getLanguage();
        try {
            byte[] wav = geminiTts.isConfigured()
                ? geminiTts.synthesizeReply(text, lang) : null;
            if (wav != null && wav.length > 44) {
                String audio = java.util.Base64.getEncoder().encodeToString(wav);
                sendJson(ctx.ws, Map.of(
                    "type", "ai_audio",
                    "audio", audio,
                    "contentType", "audio/wav",
                    "ttsProvider", "gemini",
                    "urgent", urgent));
            } else {
                sendJson(ctx.ws, Map.of(
                    "type", "ai_text",
                    "text", text,
                    "ttsProvider", "app_local",
                    "urgent", urgent));
            }
        } catch (Exception e) {
            log.warn("speech delivery failed: {}", e.getMessage());
            sendJson(ctx.ws, Map.of(
                "type", "ai_text",
                "text", text,
                "ttsProvider", "app_local",
                "urgent", urgent));
        }
        sendJson(ctx.ws, Map.of("type", "status", "value", "LISTENING"));
    }

    private double rms(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return 0;
        long sum = 0;
        int samples = pcm.length / 2;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (long) sample * sample;
        }
        return Math.sqrt((double) sum / Math.max(1, samples));
    }

    private FirstAidOrchestrator.MedicalContext medicalContext(String emergencyCode) {
        return users.findByEmergencyCode(emergencyCode)
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

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        VoiceContext ctx = contexts.remove(ws.getId());
        if (ctx == null) return;
        var session = ctx.session;
        if (session != null && "ACTIVE".equals(session.getState())) {
            // Honest DISCONNECTED state: incident persists; the bystander may
            // reconnect (browser refresh / network loss) - spec PART 9.
            session.setState("DISCONNECTED");
            session.setLastActivityAt(Instant.now());
            sessionRepository.save(session);
            logHistory(session, "SYSTEM", "Connection lost; session kept for reconnection");
        }
    }

    private void endSession(VoiceContext ctx, String reason) {
        var session = ctx.session;
        session.setState("ENDED");
        session.setEndReason(reason);
        session.setEndedAt(Instant.now());
        sessionRepository.save(session);
        logHistory(session, "SYSTEM", "Session ended by bystander (" + reason + ")");
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
}
