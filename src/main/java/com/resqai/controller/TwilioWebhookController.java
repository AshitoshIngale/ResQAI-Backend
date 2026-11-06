package com.resqai.controller;

import com.resqai.model.BystanderReportDocument;
import com.resqai.model.EmergencyProfileDocument;
import com.resqai.model.UserDocument;
import com.resqai.repository.BystanderReportRepository;
import com.resqai.repository.EmergencyProfileRepository;
import com.resqai.repository.UserRepository;
import com.resqai.service.EmergencyCallScriptService;
import com.resqai.service.FirstAidOrchestrator;
import com.resqai.service.SarvamTtsService;
import com.resqai.service.StateLanguageResolver;
import com.resqai.service.TwilioVoiceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Twilio-facing webhook endpoints for the automated emergency call.
 *
 * Security: every endpoint validates X-Twilio-Signature with the configured
 * auth token. When no token is configured the endpoints are CLOSED (404) —
 * they must never serve medical speech content unauthenticated. Medical data
 * leaves only as TwiML/audio in Twilio's own signed request, never in logs.
 *
 * One call, three languages: Sarvam TTS audio for every segment it can
 * synthesize; Twilio <Say> as fallback for English and Hindi; regional
 * segments without synthesis are skipped rather than misread in the wrong
 * voice (English + Hindi always carry the full script).
 */
@RestController
@RequestMapping("/api/v1")
public class TwilioWebhookController {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(TwilioWebhookController.class);

    private final BystanderReportRepository reports;
    private final UserRepository users;
    private final EmergencyProfileRepository profiles;
    private final TwilioVoiceService voice;
    private final SarvamTtsService tts;
    private final EmergencyCallScriptService scripts;
    private final com.resqai.repository.EmergencySmsIncidentRepository smsIncidents;
    private final FirstAidOrchestrator firstAid;
    private final com.resqai.repository.BystanderCallSessionRepository sessions;
    private final com.resqai.repository.VoiceConversationHistoryRepository conversations;
    private final com.resqai.repository.FirstAidGuidanceLogRepository guidanceLogs;

    @Value("${resqai.public-base-url:}")
    private String publicBaseUrl;

    public TwilioWebhookController(BystanderReportRepository reports,
                                   UserRepository users,
                                   EmergencyProfileRepository profiles,
                                   TwilioVoiceService voice,
                                   SarvamTtsService tts,
                                   EmergencyCallScriptService scripts,
                                   com.resqai.repository.EmergencySmsIncidentRepository smsIncidents,
                                   FirstAidOrchestrator firstAid,
                                   com.resqai.repository.BystanderCallSessionRepository sessions,
                                   com.resqai.repository.VoiceConversationHistoryRepository conversations,
                                   com.resqai.repository.FirstAidGuidanceLogRepository guidanceLogs) {
        this.reports = reports;
        this.users = users;
        this.profiles = profiles;
        this.voice = voice;
        this.tts = tts;
        this.scripts = scripts;
        this.smsIncidents = smsIncidents;
        this.firstAid = firstAid;
        this.sessions = sessions;
        this.conversations = conversations;
        this.guidanceLogs = guidanceLogs;
    }

    /** TwiML for the emergency call; Twilio fetches it (signed POST) on answer. */
    @PostMapping(value = "/twiml/incident/{reportId}", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> twiml(
            @PathVariable String reportId,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            @RequestParam Map<String, String> params) {

        if (!voice.shouldValidateSignatures()) return ResponseEntity.notFound().build();
        if (!signatureValid(signature, "/api/v1/twiml/incident/" + reportId, params)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Optional<BystanderReportDocument> found = reports.findByReportId(reportId);
        if (found.isEmpty() || !"VERIFIED".equals(found.get().getIdentityVerification())) {
            return ResponseEntity.ok(sayOnlyTwiml(
                "This is ResQAI's automated emergency reporting assistant. " +
                "The referenced emergency report could not be found. Please hang up."));
        }
        BystanderReportDocument report = found.get();
        AuthorizedMedical medical = loadAuthorizedMedical(report.getEmergencyCode());
        StateLanguageResolver.Resolution regional =
            StateLanguageResolver.resolve(report.getLatitude(), report.getLongitude());

        List<EmergencyCallScriptService.Segment> segments = scripts.buildScript(
            report.getCategory(), report.getLocationName(),
            report.getLatitude(), report.getLongitude(),
            medical.name, medical.bloodGroup, medical.allergies, medical.conditions, regional);

        // Twilio aborts webhooks after ~15 seconds. This handler therefore
        // NEVER calls the network: cached Sarvam audio -> <Play>; anything
        // cold -> Twilio's built-in <Say> for EN/HI (same words, instant),
        // and a background warm so the NEXT fetch has the regional audio.
        List<String[]> toWarm = new java.util.ArrayList<>();
        StringBuilder twiml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Response>\n");
        twiml.append("  <Pause length=\"1\"/>\n");
        for (EmergencyCallScriptService.Segment segment : segments) {
            SarvamTtsService.TtsResult audio =
                tts.synthesizeCachedOnly(segment.text(), segment.languageCode());
            if (audio.status() == SarvamTtsService.Status.SYNTHESIZED) {
                String key = SarvamTtsService.cacheKey(segment.text(), segment.languageCode());
                twiml.append("  <Play>")
                    .append(publicUrl("/api/v1/tts/incident/" + reportId + "/" + key))
                    .append("</Play>\n");
            } else {
                toWarm.add(new String[]{segment.text(), segment.languageCode()});
                if ("en-IN".equals(segment.languageCode()) || "hi-IN".equals(segment.languageCode())) {
                    // Instant fallback: Twilio's own voice reads the SAME script.
                    twiml.append("  <Say language=\"").append(segment.languageCode()).append("\">")
                        .append(escapeXml(segment.text())).append("</Say>\n");
                }
                // Regional segment without cached audio is skipped for THIS
                // fetch — never read in a wrong-language voice; EN + HI
                // already carry every fact, and the warm makes it play next time.
            }
            twiml.append("  <Pause length=\"1\"/>\n");
        }
        if (!toWarm.isEmpty()) {
            tts.prewarm(toWarm);
        }
        twiml.append("</Response>");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(twiml.toString());
    }

    /**
     * TwiML for SMS-TRIGGERED ambulance calls (spec PART 5). Uses Twilio's
     * own <Say> - no Sarvam cache dependency - repeating the message twice,
     * in the incident's language (en/hi/mr), then a pause for acknowledgment.
     */
    @PostMapping(value = "/twiml/sms/{incidentId}", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> smsTwiml(
            @PathVariable String incidentId,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            @RequestParam Map<String, String> params) {
        if (!voice.shouldValidateSignatures()) return ResponseEntity.notFound().build();
        if (!signatureValid(signature, "/api/v1/twiml/sms/" + incidentId, params)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        com.resqai.model.EmergencySmsIncidentDocument incident =
            smsIncidents.findByIncidentId(incidentId);
        if (incident == null) {
            return ResponseEntity.ok(sayOnlyTwiml(
                "This is ResQAI automated emergency assistance. The referenced " +
                "emergency could not be found. Please hang up."));
        }
        String lang = incident.getPreferredLanguage() == null
            ? "en" : incident.getPreferredLanguage().toLowerCase();
        String voiceLang = switch (lang) {
            case "hi" -> "hi-IN";
            case "mr" -> "hi-IN"; // Twilio has no Marathi voice; Hindi closest
            default -> "en-IN";
        };
        String name = incident.getPatientName() == null ? "the patient" : incident.getPatientName();
        String category = incident.getEmergencyCategory() == null
            ? "emergency" : incident.getEmergencyCategory();
        String blood = incident.getBloodGroup() == null ? "unknown" : incident.getBloodGroup();
        String warnings = incident.getMedicalConditions() == null
            ? "none reported" : incident.getMedicalConditions();
        String location = (incident.getLatitude() != null && incident.getLongitude() != null)
            ? String.format("latitude %.4f, longitude %.4f",
                incident.getLatitude(), incident.getLongitude())
            : "GPS coordinates unavailable";
        String allergies = incident.getAllergies() == null ? "none reported" : incident.getAllergies();

        String script = String.format(
            "Hello. This is ResQAI automated emergency assistance. An emergency alert " +
            "has been received for %s. The reported emergency is %s. The blood group is %s. " +
            "Known allergies: %s. Medical conditions: %s. The incident location is %s. " +
            "Please acknowledge this emergency information and send an ambulance immediately.",
            name, category, blood, allergies, warnings, location);
        String repeat = String.format(
            "Repeating: emergency for %s, %s, at %s. Please respond immediately.",
            name, category, location);

        StringBuilder twiml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Response>\n");
        twiml.append("  <Pause length=\"1\"/>\n");
        twiml.append("  <Say language=\"").append(voiceLang).append("\">")
            .append(escapeXml(script)).append("</Say>\n");
        twiml.append("  <Pause length=\"1\"/>\n");
        twiml.append("  <Say language=\"").append(voiceLang).append("\">")
            .append(escapeXml(repeat)).append("</Say>\n");
        twiml.append("  <Pause length=\"3\"/>\n");
        twiml.append("</Response>");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(twiml.toString());
    }

    /**
     * Serves one previously-synthesized TTS segment as WAV (signed).
     * Accepts GET *and* POST: Twilio's <Play> fetches audio via GET by
     * default — a POST-only mapping returned 405 and made real calls play
     * the "application error" message even though POST verifications passed.
     */
    @RequestMapping(value = "/tts/incident/{reportId}/{cacheKey}",
        method = {org.springframework.web.bind.annotation.RequestMethod.GET,
                  org.springframework.web.bind.annotation.RequestMethod.POST},
        produces = "audio/wav")
    public ResponseEntity<byte[]> ttsAudio(
            @PathVariable String reportId,
            @PathVariable String cacheKey,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            @RequestParam Map<String, String> params) {
        if (!voice.shouldValidateSignatures()) return ResponseEntity.notFound().build();
        if (!signatureValid(signature, "/api/v1/tts/incident/" + reportId + "/" + cacheKey, params)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        byte[] wav = tts.cachedAudio(cacheKey);
        if (wav == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(wav);
    }

    /**
     * Status callback: records Twilio's honest final call state. "completed"
     * means the call ENDED — never interpreted as a dispatch confirmation.
     */
    @PostMapping("/twilio/status/{reportId}")
    public ResponseEntity<Void> status(
            @PathVariable String reportId,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            @RequestParam Map<String, String> params) {
        if (!voice.shouldValidateSignatures()) return ResponseEntity.notFound().build();
        if (!signatureValid(signature, "/api/v1/twilio/status/" + reportId, params)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String callStatus = params == null ? null : params.get("CallStatus");
        reports.findByReportId(reportId).ifPresent(report -> {
            report.setCallDetail("twilio call status: " + (callStatus == null ? "unknown" : callStatus));
            report.setServerUpdatedAt(java.time.Instant.now());
            reports.save(report);
        });
        return ResponseEntity.ok().build();
    }

    // ================= Bystander real-time AI first-aid agent (PART 7) =================
    // Multi-turn CONVERSATION driven by Twilio <Gather input="speech"> with
    // barge-in: the bystander speaks naturally, Twilio transcribes, the
    // deterministic first-aid engine adapts the next step. State persists per
    // turn in Mongo (bystander_call_sessions) so webhook round-trips never
    // lose the conversation. NOT a one-shot announcement or IVR menu.

    /** Call start: create the session, speak the intro, ask the first step. */
    @PostMapping(value = "/twiml/bystander/{reportId}", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> bystanderStart(
            @PathVariable String reportId,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            @RequestParam Map<String, String> params) {
        if (!voice.shouldValidateSignatures()) return ResponseEntity.notFound().build();
        // Number B belongs to ACCOUNT B: validate with ACCOUNT B's token.
        if (!bystanderSignatureValid(signature,
                "/api/v1/twiml/bystander/" + reportId, params)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var reportOpt = reports.findByReportId(reportId);
        if (reportOpt.isEmpty() || !"VERIFIED".equals(reportOpt.get().getIdentityVerification())) {
            return ResponseEntity.ok(sayOnlyTwiml(
                "This is ResQAI's AI first-aid assistant. The referenced emergency could not be found. Please hang up."));
        }
        var report = reportOpt.get();
        String callSid = params.getOrDefault("CallSid", "");
        String lang = normalizeLang(report.getBystanderLanguage());

        FirstAidOrchestrator.MedicalContext medical = loadMedicalContext(report.getEmergencyCode());
        com.resqai.model.BystanderCallSessionDocument session =
            sessions.findByCallSid(callSid);
        if (session == null) {
            session = new com.resqai.model.BystanderCallSessionDocument();
            session.setCallSid(callSid);
            session.setReportId(reportId);
            session.setIncidentId(report.getReportId());
            session.setBystanderNumber(report.getBystanderPhone());
            session.setLanguage(lang);
            session.setMedicalContextJson(toJson(medical));
            sessions.save(session);
            logHistory(session, "SYSTEM", "AI first-aid session started (" + lang + ")");
        }

        String intro = firstAid.intro(report.getCategory(), medical, lang);
        logHistory(session, "AGENT", intro);
        List<FirstAidOrchestrator.Step> steps = firstAid.stepsFor(report.getCategory(), medical);
        session.setStepIndex(0);
        sessions.save(session);

        FirstAidOrchestrator.Step first = steps.isEmpty() ? null : steps.get(0);
        StringBuilder twiml = new StringBuilder(gatherHeader());
        twiml.append(sayXml(intro, lang));
        if (first != null) {
            twiml.append(gatherFor(reportId, sayXml(stepText(first, lang), lang), lang));
            twiml.append(noInputFallback(reportId, sayXml(reprompt(first, lang), lang)));
        } else {
            twiml.append("<Say>Goodbye.</Say><Hangup/>");
        }
        twiml.append("</Response>");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(twiml.toString());
    }

    /** Speech turn: parse the answer, advance, speak next step / instructions. */
    @PostMapping(value = "/twiml/bystander/{reportId}/turn", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> bystanderTurn(
            @PathVariable String reportId,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            @RequestParam Map<String, String> params) {
        if (!voice.shouldValidateSignatures()) return ResponseEntity.notFound().build();
        if (!bystanderSignatureValid(signature,
                "/api/v1/twiml/bystander/" + reportId + "/turn", params)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var session = sessions.findByCallSid(params.getOrDefault("CallSid", ""));
        if (session == null) {
            return ResponseEntity.ok(sayOnlyTwiml("Session not found. Goodbye."));
        }

        String capLang = session.getLanguage() == null ? "en" : session.getLanguage();
        // 3-MINUTE CAP (user requirement): the AI conversation is bounded so
        // the call is short and focused. On expiry the agent repeats the
        // victim's key details one final time and closes politely.
        if (session.getStartedAt() != null &&
            java.time.Duration.between(session.getStartedAt(), Instant.now()).getSeconds() > 180) {
            session.setSessionStatus("COMPLETED");
            session.setEndedAt(Instant.now());
            sessions.save(session);
            var victim = users.findByEmergencyCode(
                reports.findByReportId(reportId).map(r -> r.getEmergencyCode()).orElse(""))
                .filter(UserDocument::isEnabled).orElse(null);
            String details = victimSummaryLine(victim, capLang);
            String closing = closingFor(capLang);
            logHistory(session, "SYSTEM", "3-minute cap reached");
            String capScript = reminderFor(capLang) + " " + details + " " + closing;
            logHistory(session, "AGENT", capScript);
            StringBuilder cap = new StringBuilder(gatherHeader());
            cap.append(sayXml(capScript, capLang)).append("<Hangup/>");
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
                .body(cap.append("</Response>").toString());
        }
        var reportOpt = reports.findByReportId(reportId);
        String category = reportOpt.map(r -> r.getCategory()).orElse("OTHER");
        String lang = session.getLanguage() == null ? "en" : session.getLanguage();
        FirstAidOrchestrator.MedicalContext medical = fromJson(session.getMedicalContextJson());

        String speech = params.getOrDefault("SpeechResult", "");
        String answer = FirstAidOrchestrator.normalizeAnswer(speech);
        session.getAnswers().add(answer);
        logHistory(session, "BYSTANDER", speech.isBlank() ? "(silence)" : speech);

        List<FirstAidOrchestrator.Step> steps =
            firstAid.stepsFor(category, medical);
        int idx = session.getStepIndex();
        List<FirstAidOrchestrator.Step> cautions =
            firstAid.medicalCautions(category, medical, lang);

        StringBuilder twiml = new StringBuilder(gatherHeader());

        // PERIODIC VICTIM-DETAILS REMINDER: the bystander is busy and may
        // forget who they are helping - re-state the essentials periodically
        // (about every 75 seconds of conversation, once at most mid-flow).
        if (!session.isDetailsReminded() && idx >= 1 &&
            java.time.Duration.between(session.getStartedAt(), Instant.now()).getSeconds() > 75) {
            var victimForReminder = users.findByEmergencyCode(
                reports.findByReportId(reportId).map(r -> r.getEmergencyCode()).orElse(""))
                .filter(UserDocument::isEnabled).orElse(null);
            twiml.append(sayXml(reminderFor(lang) + " " +
                victimSummaryLine(victimForReminder, lang), lang));
            session.setDetailsReminded(true);
            sessions.save(session);
        }

        // If the PREVIOUS step was a question, acknowledge the answer briefly.
        if (idx >= 0 && idx < steps.size()
            && steps.get(idx).kind() == FirstAidOrchestrator.Kind.QUESTION) {
            String ack = switch (answer) {
                case "yes" -> switch (lang) {
                    case "hi" -> "ठीक है। ";
                    case "mr" -> "ठीक आहे. ";
                    default -> "Understood. ";
                };
                case "no" -> switch (lang) {
                    case "hi" -> "ठीक है। ";
                    case "mr" -> "ठीक आहे. ";
                    default -> "Okay. ";
                };
                default -> "";
            };
            if (!ack.isEmpty()) twiml.append(sayXml(ack, lang));
        }

        // Advance past the current step.
        int next = idx + 1;

        // Inject medical cautions right after the first instruction.
        if (next == 2 && !cautions.isEmpty()) {
            for (FirstAidOrchestrator.Step caution : cautions) {
                twiml.append(sayXml(stepText(caution, lang), lang));
                guidanceLogs.save(new com.resqai.model.FirstAidGuidanceLogDocument(
                    session.getId(), session.getIncidentId(), category, caution.key()));
                logHistory(session, "AGENT", stepText(caution, lang));
            }
        }

        while (next < steps.size()) {
            FirstAidOrchestrator.Step step = steps.get(next);
            session.setStepIndex(next);
            sessions.save(session);
            if (step.kind() == FirstAidOrchestrator.Kind.QUESTION) {
                twiml.append(gatherFor(reportId, sayXml(stepText(step, lang), lang), lang));
                twiml.append(noInputFallback(reportId, sayXml(reprompt(step, lang), lang)));
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
                    .body(twiml.append("</Response>").toString());
            }
            // INSTRUCTION: speak it, then auto-continue to the next step.
            twiml.append(sayXml(stepText(step, lang), lang));
            guidanceLogs.save(new com.resqai.model.FirstAidGuidanceLogDocument(
                session.getId(), session.getIncidentId(), category, step.key()));
            logHistory(session, "AGENT", stepText(step, lang));
            next++;
        }

        // All steps done: close calmly.
        session.setSessionStatus("COMPLETED");
        session.setEndedAt(Instant.now());
        sessions.save(session);
        String closing = switch (lang) {
            case "hi" -> "एम्बुलेंस रास्ते में है। मरीज़ के पास रहें। अलविदा।";
            case "mr" -> "रुग्णवाहिका वाटेत आहे. रुग्णाजवळ राहा. धन्यवाद.";
            default -> "The ambulance is on its way. Stay with the patient. Goodbye.";
        };
        logHistory(session, "AGENT", closing);
        twiml.append(sayXml(closing, lang)).append("<Hangup/>");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
            .body(twiml.append("</Response>").toString());
    }

    /** Status callback for the bystander AI call (independent from CALL 1). */
    @PostMapping("/twilio/status/bystander/{reportId}")
    public ResponseEntity<Void> bystanderStatus(
            @PathVariable String reportId,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
            @RequestParam Map<String, String> params) {
        if (!voice.shouldValidateSignatures()) return ResponseEntity.notFound().build();
        if (!bystanderSignatureValid(signature,
                "/api/v1/twilio/status/bystander/" + reportId, params)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        reports.findByReportId(reportId).ifPresent(report -> {
            report.setBystanderCallStatus(params.getOrDefault("CallStatus", "unknown"));
            report.setBystanderCallDetail("twilio call status update");
            reports.save(report);
        });
        return ResponseEntity.ok().build();
    }

    /** Victim essentials the bystander needs while waiting for the ambulance. */
    private String victimSummaryLine(UserDocument victim, String lang) {
        String name = (victim == null || victim.getFullName() == null) ? null : victim.getFullName();
        String notKnown = switch (lang) {
            case "hi" -> "अज्ञात";
            case "mr" -> "माहित नाही";
            default -> "not available";
        };
        String blood = null;
        if (victim != null) {
            blood = profiles.findByUserId(victim.getId())
                .map(pr -> pr.getBloodGroup()).orElse(null);
        }
        return switch (lang) {
            case "hi" -> "याद रखें: रोगी " +
                (name == null ? notKnown : name) + ", रक्त समूह " +
                (blood == null ? notKnown : blood) + ". एम्बुलेंस रास्ते में है।";
            case "mr" -> "लक्षात ठेवा: रुग्ण " +
                (name == null ? notKnown : name) + ", रक्तगट " +
                (blood == null ? notKnown : blood) + ". रुग्णवाहिका वाटेत आहे.";
            default -> "Remember: the patient is " + (name == null ? notKnown : name)
                + ", blood group " + (blood == null ? notKnown : blood)
                + ". The ambulance is on its way.";
        };
    }
    private String reminderFor(String lang) {
        return switch (lang) {
            case "hi" -> "याद दुबारा:";
            case "mr" -> "पुन्हा लक्षात:";
            default -> "As a reminder:";
        };
    }
    private String closingFor(String lang) {
        return switch (lang) {
            case "hi" -> "यह सहायता कॉल समाप्त हो रही है। मरीज़ के पास रहें। अलविदा।";
            case "mr" -> "ही मदत कॉल संपूर्ण होत आहे. रुग्णाजवळ राहा. धन्यवाद.";
            default -> "This assistance call is now ending. Stay with the patient. Goodbye.";
        };
    }

    private String stepText(FirstAidOrchestrator.Step step, String lang) {
        return switch (lang) {
            case "hi" -> step.textHi();
            case "mr" -> step.textMr();
            default -> step.textEn();
        };
    }
    private String reprompt(FirstAidOrchestrator.Step step, String lang) {
        String base = stepText(step, lang);
        String again = switch (lang) {
            case "hi" -> "कृपया फिर से बताएँ: ";
            case "mr" -> "कृपया पुन्हा सांगा: ";
            default -> "Please answer: ";
        };
        return again + base;
    }
    private String gatherHeader() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Response>\n";
    }
    /** Natural speech turn-taking with barge-in via Twilio speech input. */
    private String gatherFor(String reportId, String innerSay, String lang) {
        String speechLang = switch (lang) {
            case "hi" -> "hi-IN";
            case "mr" -> "mr-IN";
            default -> "en-IN";
        };
        return "  <Gather input=\"speech\" action=\"/api/v1/twiml/bystander/" + reportId
            + "/turn\" method=\"POST\" speechTimeout=\"auto\" language=\"" + speechLang
            + "\">\n    " + innerSay + "\n  </Gather>\n";
    }
    private String noInputFallback(String reportId, String repromptSay) {
        return "  <Redirect method=\"POST\">/api/v1/twiml/bystander/" + reportId
            + "/turn</Redirect>\n";
    }
    private String sayXml(String text, String lang) {
        String voiceLang = switch (lang) {
            case "hi" -> "hi-IN";
            case "mr" -> "hi-IN";
            default -> "en-IN";
        };
        return "<Say language=\"" + voiceLang + "\">" + escapeXml(text) + "</Say>";
    }
    private FirstAidOrchestrator.MedicalContext loadMedicalContext(String emergencyCode) {
        return users.findByEmergencyCode(emergencyCode)
            .filter(UserDocument::isEnabled)
            .map(v -> {
                var profile = profiles.findByUserId(v.getId());
                java.util.List<String> conditions = profile
                    .map(pr -> pr.getMedicalConditions() == null
                        ? java.util.List.<String>of() : pr.getMedicalConditions())
                    .orElse(java.util.List.<String>of());
                java.util.List<String> medications = profile
                    .map(pr -> pr.getCurrentMedications() == null
                        ? java.util.List.<String>of() : pr.getCurrentMedications())
                    .orElse(java.util.List.<String>of());
                String blood = profile.map(pr -> pr.getBloodGroup()).orElse(null);
                String allergies = profile.map(pr -> pr.getAllergies() == null
                    ? null : String.join(", ", pr.getAllergies())).orElse(null);
                return new FirstAidOrchestrator.MedicalContext(blood, allergies, conditions, medications);
            })
            .orElse(new FirstAidOrchestrator.MedicalContext(null, null, java.util.List.of(), java.util.List.of()));
    }
    private FirstAidOrchestrator.MedicalContext fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new FirstAidOrchestrator.MedicalContext(null, null, java.util.List.of(), java.util.List.of());
        }
        try {
            var n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            return new FirstAidOrchestrator.MedicalContext(
                n.path("bloodGroup").asText(null), n.path("allergies").asText(null),
                java.util.List.of(), java.util.List.of());
        } catch (Exception e) {
            return new FirstAidOrchestrator.MedicalContext(null, null, java.util.List.of(), java.util.List.of());
        }
    }
    private String toJson(FirstAidOrchestrator.MedicalContext ctx) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ctx);
        } catch (Exception e) {
            return "{}";
        }
    }
    private void logHistory(com.resqai.model.BystanderCallSessionDocument session,
                            String role, String message) {
        try {
            conversations.save(new com.resqai.model.VoiceConversationHistoryDocument(
                session.getId(), session.getIncidentId(), role, message));
        } catch (Exception e) {
            log.warn("history write failed: {}", e.getMessage());
        }
    }
    private String normalizeLang(String raw) {
        return switch (raw == null ? "en" : raw.toLowerCase()) {
            case "hi" -> "hi";
            case "mr" -> "mr";
            default -> "en";
        };
    }

    // ---- helpers ----

    private record AuthorizedMedical(String name, String bloodGroup,
                                     List<String> allergies, List<String> conditions) {}

    /** Loads ONLY the authorized spoken fields from the server's own record. */
    private AuthorizedMedical loadAuthorizedMedical(String emergencyCode) {
        Optional<UserDocument> victim = users.findByEmergencyCode(emergencyCode)
            .filter(UserDocument::isEnabled);
        if (victim.isEmpty()) return new AuthorizedMedical(null, null, null, null);
        String name = blankToNull(victim.get().getFullName());
        String bloodGroup = null;
        List<String> allergies = null;
        List<String> conditions = null;
        Optional<EmergencyProfileDocument> profile = profiles.findByUserId(victim.get().getId());
        if (profile.isPresent()) {
            bloodGroup = blankToNull(profile.get().getBloodGroup());
            allergies = cleanList(profile.get().getAllergies());
            conditions = cleanList(profile.get().getMedicalConditions());
        }
        return new AuthorizedMedical(name, bloodGroup, allergies, conditions);
    }

    /**
     * Signature check. The URLs Twilio calls are exactly the ones passed in
     * the Calls API (no query string), and the signed parameter set is every
     * request parameter (query + form body, as Spring merges them here).
     */
    /** Bystander webhooks authenticate against ACCOUNT B's auth token. */
    private boolean bystanderSignatureValid(String signature, String path, Map<String, String> params) {
        Map<String, String> all = params == null ? new HashMap<>() : params;
        String base = publicUrl(path);
        if (voice.isBystanderAccountConfigured()) {
            return voice.isBystanderSignatureValid(signature, base, all);
        }
        // No account B: fall back to the main account's token.
        return voice.isSignatureValid(signature, base, all);
    }

    private boolean signatureValid(String signature, String path, Map<String, String> params) {
        Map<String, String> all = params == null ? new HashMap<>() : params;
        return voice.isSignatureValid(signature, publicUrl(path), all);
    }

    /** Absolute public URL exactly as Twilio addressed it (no query string). */
    private String publicUrl(String path) {
        String base = (publicBaseUrl == null || publicBaseUrl.isBlank())
            ? "https://resqai-backend-n128.onrender.com" : publicBaseUrl.replaceAll("/$", "");
        return base + path;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static List<String> cleanList(List<String> list) {
        if (list == null) return null;
        List<String> clean = list.stream().filter(v -> v != null && !v.isBlank()).toList();
        return clean.isEmpty() ? null : clean;
    }

    private static String sayOnlyTwiml(String text) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Response><Say>"
            + escapeXml(text) + "</Say></Response>";
    }

    private static String escapeXml(String s) {
        return s == null ? "" : s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
