package com.resqai.controller;

import com.resqai.model.BystanderReportDocument;
import com.resqai.model.EmergencyProfileDocument;
import com.resqai.model.UserDocument;
import com.resqai.repository.BystanderReportRepository;
import com.resqai.repository.EmergencyProfileRepository;
import com.resqai.repository.UserRepository;
import com.resqai.service.EmergencyCallScriptService;
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

    private final BystanderReportRepository reports;
    private final UserRepository users;
    private final EmergencyProfileRepository profiles;
    private final TwilioVoiceService voice;
    private final SarvamTtsService tts;
    private final EmergencyCallScriptService scripts;

    @Value("${resqai.public-base-url:}")
    private String publicBaseUrl;

    public TwilioWebhookController(BystanderReportRepository reports,
                                   UserRepository users,
                                   EmergencyProfileRepository profiles,
                                   TwilioVoiceService voice,
                                   SarvamTtsService tts,
                                   EmergencyCallScriptService scripts) {
        this.reports = reports;
        this.users = users;
        this.profiles = profiles;
        this.voice = voice;
        this.tts = tts;
        this.scripts = scripts;
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
            report.getCategory(), report.getLatitude(), report.getLongitude(),
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
        }
        if (!toWarm.isEmpty()) {
            tts.prewarm(toWarm);
        }
        twiml.append("</Response>");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(twiml.toString());
    }

    /** Serves one previously-synthesized TTS segment as WAV (Twilio Play fetches it, signed). */
    @PostMapping(value = "/tts/incident/{reportId}/{cacheKey}", produces = "audio/wav")
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
