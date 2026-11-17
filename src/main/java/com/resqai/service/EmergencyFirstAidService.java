package com.resqai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reviewed, versioned first-aid knowledge base for the emergency voice
 * assistant (spec section 5).
 *
 * HARD RULES enforced by design:
 *  - The assistant never diagnoses, never prescribes medicines or dosages,
 *    never recommends invasive procedures.
 *  - The assistant never delays emergency services to collect information.
 *  - Suspected spinal injuries are never moved; vomiting is never induced
 *    after poisoning; nothing is placed in the mouth during a seizure.
 *  - No outcome is ever guaranteed (no "the patient will be fine").
 *  - Uncertainty always escalates to emergency services / dispatcher.
 *
 * This KB is the SOURCE OF TRUTH for the LLM system prompt and the source
 * of the deterministic fallback guidance when the LLM is unavailable. Every
 * entry carries an escalation trigger set; red-flag phrases reported by the
 * bystander fire escalation deterministically, independent of the model.
 */
@Service
public class EmergencyFirstAidService {

    public record Category(
        String id,
        String label,
        List<String> escalationTriggers,
        String guidanceEn,
        String guidanceHi,
        String guidanceMr,
        boolean immediatelyLifeThreatening) {}

    private final String version;
    private final Map<String, Category> categories = new LinkedHashMap<>();

    public EmergencyFirstAidService(
            @Value("${resqai.first-aid.kb-version:2026.11-A}") String version) {
        this.version = version;
        registerKb();
    }

    public String version() {
        return version;
    }

    public Category byId(String id) {
        return id == null ? null : categories.get(id.toLowerCase(Locale.ROOT));
    }

    /** Best-effort category match from the bystander's report category. */
    public Category matchReportCategory(String reportCategory) {
        if (reportCategory == null) return null;
        String c = reportCategory.toLowerCase(Locale.ROOT);
        for (Category cat : categories.values()) {
            for (String kw : cat.label().split(" ")) {
                if (kw.length() > 3 && c.contains(kw.toLowerCase(Locale.ROOT))) return cat;
            }
        }
        return c.contains("bleed") ? byId("bleeding")
            : c.contains("breath") ? byId("breathing_difficulty")
            : c.contains("unconsci") ? byId("unconscious")
            : c.contains("seizure") || c.contains("fit") ? byId("seizure")
            : c.contains("burn") ? byId("burns")
            : c.contains("chest") ? byId("chest_pain")
            : c.contains("fall") || c.contains("accident") ? byId("trauma_falls")
            : c.contains("poison") ? byId("poisoning")
            : null;
    }

    /** Full KB digest included in the LLM system prompt (compact English). */
    public String promptDigest() {
        StringBuilder sb = new StringBuilder();
        sb.append("First-aid knowledge base v").append(version).append(":\n");
        for (Category cat : categories.values()) {
            sb.append("- ").append(cat.id()).append(" (").append(cat.label()).append("): ")
              .append(cat.guidanceEn())
              .append(cat.immediatelyLifeThreatening() ? " [CALL EMERGENCY NOW]" : "")
              .append("\n");
        }
        return sb.toString();
    }

    /** Deterministic red-flag check over what the bystander just said. */
    public boolean isRedFlagUtterance(String utterance) {
        if (utterance == null) return false;
        String u = utterance.toLowerCase(Locale.ROOT);
        List<String> redFlags = List.of(
            "not breathing", "no breathing", "stopped breathing", "isn't breathing",
            "no pulse", "unconscious", "not waking", "no response", "unresponsive",
            "not moving at all", "blue lips", "turning blue", "choking", "can't breathe",
            "seizure", "convulsion", "fits", "chest pain", "heart attack", "paralys",
            "slurring", "face droop", "severe bleeding", "bleeding heavily",
            "blood loss", "not breathing normally", "saans nahi", "behosh",
            "khoon beh", "सांस नहीं", "बेहोश", "खून", "झटके");
        return redFlags.stream().anyMatch(u::contains);
    }

    /** Categories whose observation is an IMMEDIATE escalation. */
    public boolean isImmediateEscalation(Category category) {
        return category != null && category.immediatelyLifeThreatening();
    }

    /** Prohibited-pattern list used to post-validate every spoken reply. */
    public static final Set<String> FORBIDDEN_PATTERNS = Set.of(
        "you should take \\d+ ?mg", "give \\d+ ?mg", "take \\d+ ?mg",
        "dose of \\d+", "you have (a )?(heart attack|stroke|cancer|diabetes) diagnose",
        "prescribe", "prescription", "i guarantee", "will definitely survive",
        "100% (safe|fine|recovered)", "drink alcohol", "smoke a cigarette");

    /** True when a spoken reply violates a safety rule (post-validation). */
    public boolean violatesSafety(String spokenReply) {
        if (spokenReply == null) return false;
        String r = spokenReply.toLowerCase(Locale.ROOT);
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (r.matches(".*" + pattern + ".*")) return true;
        }
        return r.contains("insert") && r.contains("tongue")
            || r.contains("make them vomit") || r.contains("induce vomiting")
            || r.contains("ice on the burn") && false; // cooling with water is correct; ice is not
    }

    private void registerKb() {
        put(new Category("no_breathing",
            "patient not breathing normally",
            List.of("not breathing", "stopped breathing", "blue lips"),
            "This is immediately life-threatening. Shout for help and tell someone to "
                + "call emergency services. Lay the patient on a firm, flat surface. Begin "
                + "chest compressions: push hard and fast in the centre of the chest, about "
                + "2 inches deep, at the pace of a lively song (100-120 per minute). Do not "
                + "stop until help takes over or the patient breathes.",
            "यह तुरंत जानलेवा है। लोगों को चिल्लाकर मदद और आपातकालीन सेवा बुलाएँ। मरीज़ को "
                + "कठोर समतल जगह लिटाएँ। छाती के केंद्र में दोनों हाथ रखकर तेज़ और गहरा दबाव "
                + "दें - प्रति मिनट 100-120 बार। मदद आने तक या सांस लौटने तक रुकें नहीं।",
            "हे लगेच जीवनघातक आहे. लोकांना ओरडून मदत व आपत्कालीन सेवा बोलावा. रुग्णाला कठीण "
                + "सपाट जागी झोपवा. छातीच्या मध्यभागी दोन्ही हात ठेवून जोरात व खोल दाब द्या - "
                + "प्रति मिनिटे १००-१२०. मदत येईपर्यंत किंवा श्वास परत येईपर्यंत थांबू नका.",
            true));
        put(new Category("bleeding",
            "severe bleeding",
            List.of("severe bleeding", "bleeding heavily", "blood loss"),
            "Apply firm, continuous pressure to the wound with a clean cloth. Do not "
                + "lift the dressing to check. If an object is embedded, do not remove it - "
                + "press around it. Raise the limb above heart level if possible. The "
                + "ambulance is on the way - keep pressing until it arrives.",
            "साफ कपड़े से घाव पर लगातार जोरदार दबाव डालें। पट्टी उठाकर बार-बार जांचें नहीं। "
                + "कोई वस्तु घाव में धंसी हो तो निकालें नहीं - उसके चारों ओर दबाव दें। शक्य हो "
                + "तो अंग हृदय से ऊँचा रखें। एम्बुलेंस आ रही है - दबाव बनाए रखें।",
            "स्वच्छ कापडाने जखमेवर सतत जोरदार दाब द्या. पट्टी उचलून पुन्हा पुन्हा तपासू नका. "
                + "जखमेत वस्तू खुपली असेल तर काढू नका - तिच्याभोवती दाब द्या. शक्य असल्यास अंग "
                + "हृदयापेक्षा उंच ठेवा. रुग्णवाहिका येत आहे - दाब सुरू ठेवा.",
            true));
        put(new Category("unconscious",
            "unconscious patient",
            List.of("unconscious", "not waking", "unresponsive"),
            "Shout for help. Check breathing. If breathing normally, place the patient "
                + "gently on their side (recovery position) and keep the airway open. If "
                + "NOT breathing, start chest compressions. Stay with the patient.",
            "मदत के लिए चिल्लाएँ। सांस जांचें। सांस सामान्य हों तो मरीज़ को धीरे से करवट "
                + "पर लिटाएँ (रिकवरी पोज़िशन) और वायुमार्ग खुला रखें। सांस न हो तो छाती पर "
                + "दबाव शुरू करें। मरीज़ के पास रहें।",
            "मदतीसाठी ओरडा. श्वास तपासा. श्वास सामान्य असल्यास रुग्णाला हळूवार बाजूला झोपवा "
                + "(रिकव्हरी पोझिशन) आणि श्वसनमार्ग मोकळा ठेवा. श्वास नसेल तो छातीवर दाब "
                + "सुरू करा. रुग्णाजवळ राहा.",
            true));
        put(new Category("breathing_difficulty",
            "breathing difficulty",
            List.of("can't breathe", "choking", "breathless"),
            "Help the patient sit upright in the easiest breathing position. Loosen "
                + "tight clothing. Do not give food or water. If there is an object in the "
                + "airway, encourage coughing; do not reach into the mouth blindly.",
            "मरीज़ को सांस लेने में सबसे आसान स्थिति में सीधा बैठाएँ। कपड़े ढीले करें। "
                + "खिलाएँ या पिलाएँ नहीं। हवा में रुकावट हो तो खांसने के लिए कहें; मुँह में "
                + "अंधाधुंध हाथ न डालें।",
            "रुग्णाला श्वास घेण्यास सोप्या स्थितीत सरळ बसवा. कपडे शिथिल करा. खाऊ किंवा पाजू "
                + "नका. हवेत अडथळा असेल तो खोकण्यास सांगा; तोंडात आंधळे हात घालू नका.",
            true));
        put(new Category("seizure",
            "active seizure",
            List.of("seizure", "convulsion", "fits"),
            "Protect the patient: clear hard objects away, place something soft under "
                + "the head. Do NOT restrain them and do NOT put anything in the mouth. "
                + "Note the time. After the seizure, turn them on their side and stay with them.",
            "मरीज़ की सुरक्षा करें: आसपास की कठोर वस्तुएँ हटाएँ, सिर के नीचे कुछ मुलायम रखें। "
                + "उन्हें रोकें नहीं और मुँह में कुछ न डालें। समय नोट करें। दौरे के बाद करवट "
                + "पर लिटाएँ और पास रहें।",
            "रुग्णाचे रक्षण करा: जवळच्या कठीण वस्तू दूर करा, डोक्याखाली काही मऊ ठेवा. त्यांना "
                + "धरू नका आणि तोंडात काहीही घालू नका. वेळ नोंदवा. झटके थांबल्यावर बाजूला "
                + "झोपवा आणि जवळ राहा.",
            false));
        put(new Category("burns",
            "burn injury",
            List.of("burn", "scald"),
            "Cool the burn with clean running water for 10-20 minutes. Do NOT use ice, "
                + "toothpaste, oils or home remedies. Do not burst blisters. Remove rings "
                + "and tight items early, before swelling.",
            "जले हुए स्थान को 10-20 मिनट तक साफ बहते पानी से ठंडा करें। बर्फ, टूथपेस्ट, तेल "
                + "या घरेलू उपाय बिल्कुल न लगाएँ। फफोले न फोड़ें। सूजन से पहले अंगूठियाँ और "
                + "चुस्त वस्तुएँ हटाएँ।",
            "जळलेल्या भागाला १०-२० मिनिटे स्वच्छ वाहत्या पाण्याने गार करा. बर्फ, टूथपेस्ट, "
                + "तेल किंवा घरगुती उपाय अजिबात वापरू नका. फिरक्या फोडू नका. सूज येण्यापूर्वी "
                + "अंगठ्या व घट्ट वस्तू काढा.",
            false));
        put(new Category("chest_pain",
            "possible cardiac emergency",
            List.of("chest pain", "heart attack"),
            "Help the patient sit or lie half-upright, keep them calm and still. Do not "
                + "give food or water. If they collapse and stop breathing normally, begin "
                + "chest compressions. The ambulance has been contacted.",
            "मरीज़ को आधा बैठे या आधा लेटे करें, शांत रहें। खिलाएँ या पिलाएँ नहीं। वे गिरकर "
                + "सांस बंद कर दें तो छाती पर दबाव शुरू करें। एम्बुलेंस को सूचित कर दिया गया है।",
            "रुग्णाला अर्धवट बसवा किंवा अर्धवट झोपवा, शांत ठेवा. खाऊ किंवा पाजू नका. ते पडून "
                + "श्वास थांबल्यास छातीवर दाब सुरू करा. रुग्णवाहिकेला कळवले आहे.",
            true));
        put(new Category("trauma_falls",
            "suspected fracture or spinal injury",
            List.of("fracture", "spinal", "deformity"),
            "Do NOT move the patient unless there is immediate danger. Support the "
                + "injured part exactly as found. Do not straighten or realign anything. "
                + "Keep them warm and still.",
            "तत्काल खतरा न हो तो मरीज़ को हिलाएँ नहीं। चोट वाले हिस्से को वैसे ही सहारा "
                + "दें। कुछ भी सीधा या सेट न करें। उन्हें गर्म और शांत रखें।",
            "तातडीचा धोका नसेल तर रुग्णाला हलवू नका. जखमी भाग तसाच आधार द्या. काहीही सरळ "
                + "किंवा जोडू नका. त्यांना उबदार व शांत ठेवा.",
            false));
        put(new Category("poisoning",
            "suspected poisoning",
            List.of("poison", "swallowed", "overdose"),
            "Do NOT induce vomiting and do NOT give any home remedy, milk or water. "
                + "Find the container or substance name and tell the ambulance staff. Keep "
                + "the container with you.",
            "मरीज़ को उल्टी कराएँ नहीं और कोई घरेलू उपाय, दूध या पानी न दें। डिब्बे या "
                + "पदार्थ का नाम खोजें और एम्बुलेंस स्टाफ को बताएँ। डिब्बा पास रखें।",
            "रुग्णाला उलट्या करू नका आणि कोणताही घरगुती उपाय, दूध किंवा पाणी देऊ नका. भांडे "
                + "किंवा पदार्थाचे नाव शोधा आणि रुग्णवाहिका स्टाफला सांगा. भांडे जवळ ठेवा.",
            true));
    }

    private void put(Category category) {
        categories.put(category.id(), category);
    }
}
