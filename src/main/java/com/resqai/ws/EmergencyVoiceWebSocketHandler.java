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
import com.resqai.service.SarvamSpeechService;
import com.resqai.service.SarvamStreamingTtsService;
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
 * buffers utterances with silence detection, transcribes each utterance with
 * Sarvam REST STT (verified word-perfect), and the CONVERSATION BRAIN replies:
 * Gemini (user's own API, KB-restricted, safety post-validated) with a
 * deterministic fallback when Gemini is unavailable or violates safety.
 * The reply is spoken by Sarvam streaming TTS and streamed back as audio.
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
 *   server -> {"type":"ai","text":...,"audio":"<base64 mp3>","urgent":bool}
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
    private final SarvamSpeechService sarvamSpeech;
    private final SarvamStreamingTtsService ttsService;
    private final EmergencyConversationService conversation;
    private final GeminiFirstAidService gemini;
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
            SarvamSpeechService sarvamSpeech,
            SarvamStreamingTtsService ttsService,
            EmergencyConversationService conversation,
            GeminiFirstAidService gemini,
            EmergencySituationReportService sitrepService,
            com.resqai.repository.BystanderReportRepository reports,
            com.resqai.repository.UserRepository users,
            com.resqai.repository.EmergencyProfileRepository profiles,
            EmergencyFirstAidService firstAidService) {
        this.firstAidService = firstAidService;
        this.sessionRepository = sessionRepository;
        this.historyRepository = historyRepository;
        this.sarvamSpeech = sarvamSpeech;
        this.ttsService = ttsService;
        this.conversation = conversation;
        this.gemini = gemini;
        this.sitrepService = sitrepService;
        this.reports = reports;
        this.users = users;
        this.profiles = profiles;
    }

    private static class VoiceContext {
        WebSocketSession ws;
        EmergencyVoiceSessionDocument session;
        SarvamStreamingTtsService.Stream tts;
        ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
        boolean inUtterance = false;
        int speechFrames = 0;
        int quietFrames = 0;
        boolean muted = false;
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
        // threads (STT callbacks, agent turn, TTS stream). Raw sessions are
        // single-threaded - concurrent sends kill the connection. The
        // decorator serializes them with an overflow buffer.
        ctx.ws = new ConcurrentWebSocketSessionDecorator(ws, 8000, 512 * 1024);
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
        speak(ctx, intro, false);
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
        boolean isSpeech = rms > SPEECH_RMS;

        if (!ctx.inUtterance) {
            if (isSpeech) {
                ctx.inUtterance = true;
                ctx.speechFrames = 1;
                ctx.quietFrames = 0;
                ctx.pcmBuffer.reset();
                ctx.pcmBuffer.write(pcm, 0, pcm.length);
                sendJson(ctx.ws, Map.of("type", "status", "value", "PROCESSING"));
            }
            return;
        }

        ctx.pcmBuffer.write(pcm, 0, pcm.length);
        ctx.speechFrames++;
        if (isSpeech) ctx.quietFrames = 0; else ctx.quietFrames++;

        boolean longEnough = ctx.speechFrames >= MIN_SPEECH_FRAMES;
        boolean enoughQuiet = ctx.quietFrames >= QUIET_FRAMES_TO_END;
        boolean tooLong = ctx.speechFrames >= MAX_SPEECH_FRAMES;
        if ((longEnough && enoughQuiet) || tooLong) {
            ctx.inUtterance = false;
            byte[] utterance = ctx.pcmBuffer.toByteArray();
            ctx.pcmBuffer.reset();
            ctx.speechFrames = 0;
            ctx.quietFrames = 0;
            finalizeUtterance(ctx, utterance);
        }
    }

    /** STT + brain + TTS run off the WS thread; the socket stays responsive. */
    private void finalizeUtterance(VoiceContext ctx, byte[] utterancePcm) {
        var session = ctx.session;
        sendJson(ctx.ws, Map.of("type", "status", "value", "PROCESSING"));
        Thread.ofVirtual().name("resqai-voice-turn").start(() -> {
            try {
                String langCode = switch (session.getLanguage() == null ? "en" : session.getLanguage()) {
                    case "hi" -> "hi-IN";
                    case "mr" -> "mr-IN";
                    default -> "en-IN";
                };
                byte[] wav = AudioWavUtil.pcm16ToWav(utterancePcm, SAMPLE_RATE, 1);
                var stt = sarvamSpeech.transcribe(wav, langCode);
                String transcript = stt.transcript();
                if (transcript == null || transcript.isBlank()) {
                    // Honest: the utterance could not be transcribed.
                    sendJson(ctx.ws, Map.of("type", "status", "value", "LISTENING"));
                    return;
                }
                sendJson(ctx.ws, Map.of("type", "transcript", "role", "bystander", "text", transcript));
                logHistory(session, "BYSTANDER", transcript);

                var reportOpt = reports.findByReportId(session.getIncidentId());
                String category = reportOpt.map(r -> r.getCategory()).orElse("OTHER");
                FirstAidOrchestrator.MedicalContext medical = medicalContext(session.getEmergencyCode());

                // Facts first (shared by both brains), then the reply.
                conversation.updateFacts(session, transcript);
                boolean redFlag = firstAidService.isRedFlagUtterance(transcript);
                boolean urgent = redFlag;

                String reply = null;
                if (gemini.isConfigured()) {
                    reply = gemini.reply(session, category, medical, transcript);
                }
                if (reply == null || reply.isBlank()) {
                    // Deterministic fallback: reviewed KB guidance - never silence.
                    var turn = conversation.handleUtterance(session, category, medical, transcript);
                    reply = turn.speech;
                    urgent = urgent || turn.urgent;
                } else {
                    logHistory(session, "AGENT", reply);
                }

                sitrepService.recordTimeline(session.getIncidentId(),
                    "BYSTANDER_REPORTED", transcript, redFlag);
                if (urgent) {
                    var urgentReport = sitrepService.generate(session, "URGENT", true);
                    sitrepService.relay(urgentReport, sitrepService.ambulanceDestination());
                    session.setUrgentFlagged(true);
                }

                speak(ctx, reply, urgent);
            } catch (Exception e) {
                log.error("voice turn failed: {}", e.getMessage());
                sendJson(ctx.ws, Map.of("type", "error",
                    "message", "The assistant could not process that. Please repeat."));
                sendJson(ctx.ws, Map.of("type", "status", "value", "LISTENING"));
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
        if (ctx.tts != null) try { ctx.tts.close(); } catch (Exception ignore) {}
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
}
