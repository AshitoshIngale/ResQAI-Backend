package com.resqai.service;

import com.resqai.model.EmergencyVoiceSessionDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * The conversation brain for the browser-based AI first-aid agent (spec
 * PART 5/6). NOT an IVR: it understands free-text answers (keyword matching
 * in EN/HI/MR), keeps a memory of collected facts and asked questions, and
 * adapts the next response - including urgent escalation when the bystander
 * reports the patient is not breathing.
 *
 * Safety: every instruction comes from the reviewed FirstAidOrchestrator /
 * EmergencyFirstAidService knowledge base. The engine never invents
 * procedures, never prescribes, and clearly distinguishes bystander
 * observations from guidance.
 */
@Service
public class EmergencyConversationService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyConversationService.class);

    private final EmergencyFirstAidService firstAidKb;

    public EmergencyConversationService(EmergencyFirstAidService firstAidKb) {
        this.firstAidKb = firstAidKb;
    }

    public static class AgentTurn {
        /** What the agent should SAY (may be several sentences). */
        public final String speech;
        /** True when the situation deteriorated (triggers immediate sitrep). */
        public final boolean urgent;
        /** Timeline entry describing this turn (for the condition timeline). */
        public final String timelineEntry;

        public AgentTurn(String speech, boolean urgent, String timelineEntry) {
            this.speech = speech;
            this.urgent = urgent;
            this.timelineEntry = timelineEntry;
        }
    }

    /** Processes one free-text bystander utterance and produces the response. */
    public AgentTurn handleUtterance(EmergencyVoiceSessionDocument session,
                                     String category,
                                     FirstAidOrchestrator.MedicalContext medical,
                                     String transcript) {
        String lang = session.getLanguage() == null ? "en" : session.getLanguage();
        String text = transcript == null ? "" : transcript.toLowerCase();
        Map<String, String> facts = session.getCollectedFacts();
        boolean urgent = false;
        String timelineEntry = "Bystander reported: " + transcript;

        // ---- free-text understanding (EN/HI/MR keywords) ----
        if (matchesAny(text, "not breathing", "no breathing", "isn't breathing",
                "stopped breathing", "सांस नहीं", "सांस ले नहीं", "श्वास नाही")) {
            facts.put("breathing", "no");
            urgent = true;
        } else if (matchesAny(text, "breathing", "सांस", "श्वास")) {
            facts.putIfAbsent("breathing", "yes");
        }
        if (matchesAny(text, "bleeding", "blood", "bleed", "रक्तस्राव", "खून", "रक्त वाह")) {
            facts.put("bleeding", "yes");
        }
        if (matchesAny(text, "unconscious", "not responding", "no response",
                "collapsed", "बेहोश", "बेसुध", "बेशुद्ध", "प्रतिसाद नाही")) {
            facts.put("conscious", "no");
            urgent = urgent || facts.containsKey("breathing");
        } else if (matchesAny(text, "conscious", "awake", "responding",
                "जागरूक", "जवाब दे", "जागरूक आहेत")) {
            facts.put("conscious", "yes");
        }
        if (matchesAny(text, "seizure", "convulsion", "fit", "झटक")) {
            facts.put("seizure", "yes");
        }
        if (matchesAny(text, "chest pain", "सीने में दर्द", "छातीत दुख")) {
            facts.put("chestPain", "yes");
            urgent = true;
        }
        if (matchesAny(text, "slowing", "कम हो", "कमी होत")) {
            facts.put("bleedingSlowing", "yes");
        }
        session.getObservations().add(transcript);

        // ---- choose the next spoken response ----
        StringBuilder speech = new StringBuilder();
        String ack = acknowledge(lang);
        if (!ack.isEmpty()) speech.append(ack).append(" ");

        // URGENT: not breathing -> CPR guidance immediately.
        if ("no".equals(facts.get("breathing")) && !"yes".equals(facts.get("cprGiven"))) {
            facts.put("cprGiven", "yes");
            urgent = true;
            speech.append(switch (lang) {
                case "hi" -> "सांस नहीं आ रही है - यह गंभीर है। मरीज़ को सीधे कठोर सतह पर लिटाएँ। "
                    + "दोनों हाथ छाती के बीच रखकर तेज़ दबाव दें - हर सेकंड महत्वपूर्ण है। "
                    + "जब तक मदद न आए दबाव जारी रखें।";
                case "mr" -> "श्वास न घेता असल्यास - हे गंभीर आहे. रुग्णाला सरळ कठीण जागी झोपवा. "
                    + "दोन्ही हात छातीच्या मध्यभागी ठेवून जोरदार दाब द्या - प्रत्येक सेकंद महत्त्वाचा आहे. "
                    + "मदत येईपर्यंत दाब सुरू ठेवा.";
                default -> "The patient is not breathing - this is critical. Lay them flat on a "
                    + "firm surface. Place both hands in the centre of the chest and push hard "
                    + "and fast - every second matters. Keep going until help arrives.";
            });
            return finishTurn(session, speech.toString(), urgent,
                "DETERIORATION: patient reported not breathing; CPR guidance given");
        }

        // Bleeding reported -> pressure guidance + follow-up question.
        if ("yes".equals(facts.get("bleeding")) && !"yes".equals(facts.get("pressureGiven"))) {
            facts.put("pressureGiven", "yes");
            speech.append(switch (lang) {
                case "hi" -> "साफ कपड़े या पट्टी से रक्तस्राव वाली जगह पर लगातार जोरदार दबाव डालें। "
                    + "इसे बार-बार हटाकर जांचें नहीं। कोई चीज़ घाव में धंसी हो तो उसे निकालें नहीं।";
                case "mr" -> "स्वच्छ कापड किंवा पट्टीने रक्तस्रावाच्या जागी सतत जोरदार दाब द्या. "
                    + "वारंवार काढून तपासू नका. जखमेत काही खुपले असेल तर काढू नका.";
                default -> "Apply firm, continuous pressure to the bleeding with a clean cloth "
                    + "or dressing. Do not keep lifting it to check. If anything is embedded "
                    + "in the wound, do not remove it.";
            });
            speech.append(" ").append(askInLang(lang,
                "Is the bleeding slowing down?",
                "क्या रक्तस्राव कम हो रहा है?", "रक्तस्राव कमी होत आहे का?"));
            return finishTurn(session, speech.toString(), false,
                "Bleeding reported; direct-pressure guidance given");
        }
        if ("yes".equals(facts.get("bleedingSlowing"))) {
            speech.append(switch (lang) {
                case "hi" -> "अच्छा - दबाव बनाए रखें और उस हिस्से को ऊँचा रखें। ";
                case "mr" -> "बरोबर - दाब सुरू ठेवा आणि तो भाग उंच ठेवा. ";
                default -> "Good - keep the pressure on and keep the area elevated. ";
            });
        }

        // Consciousness unknown -> ask (the single most important next fact).
        if (!facts.containsKey("conscious") && !asked(session, "conscious")) {
            markAsked(session, "conscious");
            speech.append(askInLang(lang,
                "Is the patient conscious and responding when you speak to them?",
                "क्या मरीज़ जागरूक है और जवाब दे रहा है?",
                "रुग्ण जागरूक आहेत आणि प्रतिसाद देत आहेत का?"));
            return finishTurn(session, speech.toString(), false,
                "Asked about consciousness");
        }
        // Breathing unknown -> ask.
        if (!facts.containsKey("breathing") && !"yes".equals(facts.get("cprGiven"))
            && !asked(session, "breathing")) {
            markAsked(session, "breathing");
            speech.append(askInLang(lang,
                "Is the patient breathing normally?",
                "क्या मरीज़ सामान्य रूप से सांस ले रहा है?",
                "रुग्ण सामान्यपणे श्वास घेत आहेत का?"));
            return finishTurn(session, speech.toString(), false,
                "Asked about breathing");
        }
        // Bleeding unknown -> ask.
        if (!facts.containsKey("bleeding") && !asked(session, "bleeding")) {
            markAsked(session, "bleeding");
            speech.append(askInLang(lang,
                "Is there any severe bleeding?",
                "क्या गंभीर रक्तस्राव हो रहा है?",
                "तीव्र रक्तस्राव होत आहे का?"));
            return finishTurn(session, speech.toString(), false,
                "Asked about bleeding");
        }

        // Core facts collected: give the category-appropriate care instruction.
        if (!"yes".equals(facts.get("careGiven"))) {
            facts.put("careGiven", "yes");
            speech.append(careInstruction(category, medical, lang));
            markAsked(session, "care");
            return finishTurn(session, speech.toString(), false,
                "Category care instruction given");
        }

        // Ongoing monitoring - vary the prompt, never robotic.
        speech.append(pick(monitorPrompts(), session, lang));
        return finishTurn(session, speech.toString(), false, "Monitoring prompt");
    }

    /** Intro spoken at session start (spec PART 5 conversation example). */
    public String intro(String language) {
        return switch (language) {
            case "hi" -> "नमस्ते। मैं ResQAI का आपातकालीन सहायक हूँ - एक स्वचालित प्रणाली, कोई इंसान नहीं। "
                + "मैं आपको प्राथमिक चिकित्सा में मदद करूँगा जब तक एम्बुलेंस नहीं आती। "
                + "पहले बताइए क्या हुआ है?";
            case "mr" -> "नमस्कार. मी ResQAI चा आपत्कालीन सहाय्यक आहे - एक स्वयंचलित प्रणाली, माणूस नाही. "
                + "रुग्णवाहिका न येईपर्यंत प्राथमिक उपचारात मी तुम्हाला मदत करेन. "
                + "आधी सांगा की काय झाले आहे?";
            default -> "Hello. I am the ResQAI emergency assistant - an automated system, not a "
                + "human. I will help you provide first-aid support while emergency "
                + "assistance is arranged. First, can you tell me what happened?";
        };
    }

    private List<String> monitorPrompts() {
        return List.of(
            "Any change in the patient's condition? Answer yes or no.",
            "Is the patient still breathing normally?",
            "Tell me about any new concern or change.");
    }

    private String askInLang(String lang, String en, String hi, String mr) {
        return switch (lang) {
            case "hi" -> hi;
            case "mr" -> mr;
            default -> en;
        };
    }

    private String acknowledge(String lang) {
        return switch (lang) {
            case "hi" -> "समझ गया।";
            case "mr" -> "समजले.";
            default -> "Understood.";
        };
    }

    private String pick(List<String> options, EmergencyVoiceSessionDocument session, String lang) {
        int turns = session.getAskedKeys().size();
        String chosen = options.get(turns % options.size());
        return askInLang(lang, chosen, chosen, chosen);
    }

    private String careInstruction(String category,
                                   FirstAidOrchestrator.MedicalContext medical,
                                   String lang) {
        var kb = firstAidKb.matchReportCategory(category);
        if (kb == null) {
            return askInLang(lang,
                "Stay with the patient and keep monitoring. The ambulance has been contacted.",
                "मरीज़ के पास रहें और निगरानी करते रहें। एम्बुलेंस को सूचित कर दिया गया है।",
                "रुग्णाजवळ राहा आणि निरीक्षण सुरू ठेवा. रुग्णवाहिकेला कळवले आहे.");
        }
        return switch (lang) {
            case "hi" -> kb.guidanceHi() == null || kb.guidanceHi().isBlank()
                ? kb.guidanceEn() : kb.guidanceHi();
            case "mr" -> kb.guidanceMr() == null || kb.guidanceMr().isBlank()
                ? kb.guidanceEn() : kb.guidanceMr();
            default -> kb.guidanceEn();
        };
    }

    private boolean asked(EmergencyVoiceSessionDocument session, String key) {
        return session.getAskedKeys() != null && session.getAskedKeys().contains(key);
    }

    private void markAsked(EmergencyVoiceSessionDocument session, String key) {
        if (session.getAskedKeys() == null) {
            session.setAskedKeys(new java.util.ArrayList<>());
        }
        if (!session.getAskedKeys().contains(key)) {
            session.getAskedKeys().add(key);
        }
    }

    private boolean matchesAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private AgentTurn finishTurn(EmergencyVoiceSessionDocument session,
                                 String speech, boolean urgent, String timelineEntry) {
        if (session.getAskedKeys() == null) {
            session.setAskedKeys(new java.util.ArrayList<>());
        }
        return new AgentTurn(speech, urgent, timelineEntry);
    }
}
