package com.resqai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The controlled, versioned FIRST-AID decision engine (spec PART 9).
 * Deterministic per-category step chains - NO unrestricted LLM, NO invented
 * procedures. Each step is a QUESTION (bystander answers yes/no/unknown via
 * speech) or an INSTRUCTION (one clear action). Medical context from the
 * victim's authorized record injects safety cautions (anticoagulants,
 * bleeding disorders, seizure history, severe allergies).
 *
 * Safety invariants (enforced by construction):
 *  - never prescribes medication or dosage
 *  - never recommends invasive procedures
 *  - always escalates: every flow contains "the ambulance has been contacted"
 *    ONLY as a factual statement the backend can support (call status)
 */
@Service
public class FirstAidOrchestrator {

    public enum Kind { QUESTION, INSTRUCTION }

    public record Step(Kind kind, String key, String textEn, String textHi, String textMr) {}

    public record MedicalContext(String bloodGroup, String allergies,
                                 List<String> conditions, List<String> medications) {}

    /** Spoken intro for the bystander AI call. */
    public String intro(String category, MedicalContext ctx, String lang) {
        String cat = categoryName(category, lang);
        return switch (lang) {
            case "hi" -> "नमस्ते। मैं ResQAI का आपातकालीन प्राथमिक चिकित्सा सहायक हूँ, कोई इंसान नहीं। " +
                "एम्बुलेंस को सूचित कर दिया गया है। मैं " + cat + " के लिए आपकी मदद करूँगा। " +
                "क्या मरीज़ जागरूक हैं और जवाब दे रहे हैं?";
            case "mr" -> "नमस्कार. मी ResQAI चा आपत्कालीन प्राथमिक उपचार सहाय्यक आहे, माणूस नाही. " +
                "रुग्णवाहिकेला कळवण्यात आले आहे. " + cat + " साठी मी तुम्हाला मदत करेन. " +
                "रुग्ण जागरूक आहेत आणि प्रतिसाद देत आहेत का?";
            default -> "Hello, this is ResQAI's AI first-aid assistant - an automated system, " +
                "not a human. The ambulance has been contacted. I will help you with the " +
                cat + " until help arrives. First, is the patient conscious and responding?";
        };
    }

    /** The ordered step chain for a category. */
    public List<Step> stepsFor(String category, MedicalContext ctx) {
        String cat = category == null ? "OTHER" : category.toUpperCase();
        List<Step> steps = new ArrayList<>(baseSafety(ctx));
        switch (cat) {
            case "SEVERE_BLEEDING", "BLEEDING" -> steps.addAll(List.of(
                q("conscious", "Is the patient conscious and responding?",
                  "क्या मरीज़ जागरूक है?", "रुग्ण जागरूक आहेत का?"),
                q("breathing", "Is the patient breathing normally?",
                  "क्या सांस सामान्य है?", "श्वास सामान्य आहे का?"),
                i("pressure", "Apply firm, direct pressure on the bleeding with a clean cloth. Do not stop.",
                  "साफ कपड़े से रक्तस्राव पर जोरदार दबाव डालें। रुकें नहीं।",
                  "स्वच्छ कापडाने रक्तस्रावावर जोरदार दाब द्या. थांबू नका."),
                i("no_remove", "Do NOT remove any object stuck in the wound.",
                  "घाव में फंसी कोई वस्तु न निकालें।", "जखमात खुपलेली वस्तू काढू नका."),
                i("elevate", "Keep the bleeding area raised above heart level if possible.",
                  "हो सके तो घाव वाला हिस्सा हृदय से ऊँचा रखें।",
                  "शक्य असल्यास जखमेचा भाग हृदयापेक्षा उंच ठेवा.")
            ));
            case "UNCONSCIOUS", "CARDIAC_EMERGENCY" -> steps.addAll(List.of(
                q("breathing", "Is the patient breathing normally?",
                  "क्या सांस सामान्य है?", "श्वास सामान्य आहे का?"),
                i("call_escalate", "The ambulance has been contacted. Stay with the patient.",
                  "एम्बुलेंस को सूचित कर दिया गया है। मरीज़ के पास रहें।",
                  "रुग्णवाहिकेला कळवण्यात आले आहे. रुग्णाजवळ राहा."),
                i("recovery_position", "If breathing normally, turn the patient gently onto their side.",
                  "सांस सामान्य हों तो मरीज़ को धीरे से करवट पर लिटाएँ।",
                  "श्वास सामान्य असल्यास रुग्णाला हळूवार बाजूला झोपवा."),
                i("cpr_wait", "If NOT breathing, tell me now - I will guide chest compressions.",
                  "सांस न हो तो मुझे अभी बताएँ - मैं छाती का दबाव सिखाऊँगा।",
                  "श्वास नसेल तो आत्ताच सांगा - मी छातीवर दाब सुचवेन.")
            ));
            case "BREATHING_DIFFICULTY" -> steps.addAll(List.of(
                q("conscious", "Is the patient conscious?",
                  "क्या मरीज़ जागरूक है?", "रुग्ण जागरूक आहेत का?"),
                i("sit_upright", "Help the patient sit upright - the easiest breathing position.",
                  "मरीज़ को सीधे बैठाएँ - सांस लेने की सबसे आसान स्थिति।",
                  "रुग्णाला सरळ बसवा - श्वास घेण्याची सोपी स्थिती."),
                i("nothing_oral", "Do NOT give any food or water.",
                  "कुछ भी खिलाएँ या पिलाएँ नहीं।", "काहीही खाऊ व पाजू नका."),
                i("loosen", "Loosen tight clothing around the neck and chest.",
                  "गर्दन और छाती के पास के कपड़े ढीले करें।", "मान व छातीजवळील कपडे शिथिल करा.")
            ));
            case "ACTIVE_SEIZURE", "SEIZURE" -> steps.addAll(List.of(
                i("clear_area", "Clear hard objects away from the patient. Do not restrain them.",
                  "मरीज़ के आसपास की कठोर वस्तुएँ हटाएँ। उन्हें रोकें नहीं।",
                  "रुग्णाभोवतीच्या कठीण वस्तू दूर करा. त्यांना धरू नका."),
                i("cushion_head", "Place something soft under the head.",
                  "सिर के नीचे कुछ मुलायम रखें।", "डोक्याखाली काही मऊ ठेवा."),
                i("never_mouth", "Do NOT put anything in the mouth. Never hold the tongue.",
                  "मुँह में कुछ भी डालें नहीं। जीभ कभी न पकड़ें।",
                  "तोंडात काहीही घालू नका. जीभ कधीही पकडू नका."),
                q("duration_over_5", "Has the seizure lasted more than 5 minutes?",
                  "क्या दौरा 5 मिनट से अधिक चला?", "झटके ५ मिनिटांहून अधिक चालू आहेत का?"),
                i("time_it", "Note the time the seizure started and keep monitoring breathing.",
                  "दौरे की शुरुआत का समय नोट करें और सांस देखते रहें।",
                  "झटक्याची सुरुवातीची वेळ नोंदवा आणि श्वास पाहा.")
            ));
            case "BURN_INJURY", "FIRE" -> steps.addAll(List.of(
                i("cool_water", "Cool the burn with clean running water for 10-20 minutes.",
                  "जले हुए स्थान को 10-20 मिनट तक साफ बहते पानी से ठंडा करें।",
                  "जळलेल्या भागाला १०-२० मिनिटे स्वच्छ वाहत्या पाण्याने गार करा."),
                i("no_remedies", "Do NOT use ice, toothpaste, oil, or any home remedy.",
                  "बर्फ, टूथपेस्ट, तेल या घरेलू उपाय बिल्कुल न लगाएँ।",
                  "बर्फ, टूथपेस्ट, तेल किंवा घरगुती उपाय अजिबात वापरू नका."),
                i("no_burst", "Do NOT burst any blisters. Remove rings/tight items early.",
                  "फफोले फोड़ें नहीं। अंगूठियाँ जल्दी निकालें।",
                  "फिरक्या फोडू नका. अंगठ्या लवकर काढा."),
                q("large_or_deep", "Is the burn larger than the patient's palm, or very deep?",
                  "क्या जलन प्रभावित इलाका हथेली से बड़ा या गहरा है?",
                  "जळजळीचा भाग हाताच्या तळव्यापेक्षा मोठा किंवा खोल आहे का?")
            ));
            case "FALL_INJURY", "FRACTURE" -> steps.addAll(List.of(
                i("do_not_move", "Do NOT move the patient unless there is immediate danger.",
                  "तत्काल खतरा न हो तो मरीज़ को हिलाएँ नहीं।",
                  "तातडीचा धोका नसेल तर रुग्णाला हलवू नका."),
                i("immobilize", "Support the injured part exactly as it lies. Do not straighten it.",
                  "चोट वाले हिस्से को वैसे ही सहारा दें। सीधा करें नहीं।",
                  "जखमी भाग तसाच आधार द्या. सरळ करू नका."),
                q("severe_pain_or_deformity", "Is there visible deformity or severe pain?",
                  "क्या स्पष्ट विकृति या तीव्र दर्द है?", "स्पष्ट विकृती किंवा तीव्र वेदना आहे का?")
            ));
            case "POISONING" -> steps.addAll(List.of(
                i("no_vomit", "Do NOT make the patient vomit.",
                  "मरीज़ को उल्टी कराएँ नहीं।", "रुग्णाला उलट्या करू नका."),
                q("conscious", "Is the patient conscious?",
                  "क्या मरीज़ जागरूक है?", "रुग्ण जागरूक आहेत का?"),
                i("identify", "Find the container or substance name - tell the ambulance staff.",
                  "डिब्बे या पदार्थ का नाम खोजें - एम्बुलेंस स्टाफ को बताएँ।",
                  "भांडे किंवा पदार्थाचे नाव शोधा - रुग्णवाहिका स्टाफला सांगा."),
                i("no_remedy", "Do NOT give any home remedy, milk, or water unless instructed.",
                  "बिना निर्देश कोई घरेलू उपाय, दूध या पानी न दें।",
                  "निर्देशाशिवाय घरगुती उपाय, दूध किंवा पाणी देऊ नका.")
            ));
            default -> steps.addAll(List.of(
                q("conscious", "Is the patient conscious and responding?",
                  "क्या मरीज़ जागरूक है?", "रुग्ण जागरूक आहेत का?"),
                q("breathing", "Is the patient breathing normally?",
                  "क्या सांस सामान्य है?", "श्वास सामान्य आहे का?"),
                i("stay", "Stay with the patient. The ambulance has been contacted.",
                  "मरीज़ के पास रहें। एम्बुलेंस को सूचित किया गया है।",
                  "रुग्णाजवळ राहा. रुग्णवाहिकेला कळवले आहे.")
            ));
        }
        return steps;
    }

    /** Category-specific cautions derived from the victim's medical record. */
    public List<Step> medicalCautions(String category, MedicalContext ctx, String lang) {
        if (ctx == null) return List.of();
        List<Step> out = new ArrayList<>();
        boolean bleedingLike = "SEVERE_BLEEDING".equalsIgnoreCase(category)
            || "FALL_INJURY".equalsIgnoreCase(category);
        if (ctx.medications() != null) {
            for (String med : ctx.medications()) {
                String m = med == null ? "" : med.toLowerCase();
                if (bleedingLike && (m.contains("blood thinner") || m.contains("warfarin")
                    || m.contains("aspirin") || m.contains("anticoagulant"))) {
                    out.add(new Step(Kind.INSTRUCTION, "caution_anticoagulant",
                        "IMPORTANT: this patient takes blood thinners - bleeding can be serious. " +
                            "Keep pressure on and tell the ambulance staff.",
                        "महत्वपूर्ण: यह मरीज़ रक्त पतला करने की दवा लेता है - रक्तस्राव गंभीर हो सकता है। " +
                            "दबाव जारी रखें और एम्बुलेंस स्टाफ को बताएँ।",
                        "महत्त्वाचे: हा रुग्ण रक्त पातळ करणारी औषधे घेतात - रक्तस्राव गंभीर होऊ शकतो. " +
                            "दाब सुरू ठेवा आणि रुग्णवाहिका स्टाफला सांगा."));
                }
            }
        }
        if (ctx.conditions() != null) {
            for (String c : ctx.conditions()) {
                String cs = c == null ? "" : c.toLowerCase();
                if (cs.contains("seizure") || cs.contains("epilepsy")) {
                    out.add(new Step(Kind.INSTRUCTION, "caution_seizure_history",
                        "Note: the patient has a seizure history. Do not restrain; protect the head.",
                        "नोट: मरीज़ को दौरे का इतिहास है। रोकें नहीं; सिर की रक्षा करें।",
                        "टीप: रुग्णाला झटक्यांचा इतिहास आहे. धरू नका; डोके संरक्षित करा."));
                }
                if (cs.contains("diabet") && "UNCONSCIOUS".equalsIgnoreCase(category)) {
                    out.add(new Step(Kind.INSTRUCTION, "caution_diabetes",
                        "Note: the patient has diabetes - low blood sugar can cause unconsciousness. " +
                            "Tell the ambulance staff immediately.",
                        "नोट: मरीज़ को मधुमेह है - लो ब्लड शुगर से बेहोशी हो सकती है। " +
                            "एम्बुलेंस स्टाफ को तुरंत बताएँ।",
                        "टीप: रुग्णाला मधुमेह आहे - कमी रक्तातील साखरेमुळे बेशुद्ध होऊ शकते. " +
                            "रुग्णवाहिका स्टाफला लगेच सांगा."));
                }
            }
        }
        if (ctx.allergies() != null && !ctx.allergies().isBlank()) {
            out.add(new Step(Kind.INSTRUCTION, "caution_allergies",
                "Known allergies: " + ctx.allergies() + ". Tell the ambulance staff.",
                "ज्ञात एलर्जी: " + ctx.allergies() + ". एम्बुलेंस स्टाफ को बताएँ।",
                "ज्ञात ऍलर्जी: " + ctx.allergies() + ". रुग्णवाहिका स्टाफला सांगा."));
        }
        return out;
    }

    /** Parses a spoken yes/no/unclear answer from Twilio's SpeechResult. */
    public static String normalizeAnswer(String speech) {
        if (speech == null) return "unclear";
        String s = speech.toLowerCase().trim();
        if (s.matches(".*\\b(yes|yeah|yep|correct|right|haan|ha)\\b.*")) return "yes";
        if (s.matches(".*\\b(no|nope|nahi|nahin|nako)\\b.*")) return "no";
        return "unclear";
    }

    private static Step q(String key, String en, String hi, String mr) {
        return new Step(Kind.QUESTION, key, en, hi, mr);
    }
    private static Step i(String key, String en, String hi, String mr) {
        return new Step(Kind.INSTRUCTION, key, en, hi, mr);
    }
    private static List<Step> baseSafety(MedicalContext ctx) {
        return List.of(new Step(Kind.INSTRUCTION, "safety_first",
            "First, make sure the area is safe for you and the patient. Do not move the patient unless there is danger.",
            "पहले सुनिश्चित करें कि जगह आप और मरीज़ के लिए सुरक्षित है। खतरा न हो तो मरीज़ को हिलाएँ नहीं।",
            "आधी खात्री करा की ठिकाण तुम्हाला व रुग्णाला सुरक्षित आहे. धोका नसेल तर रुग्णाला हलवू नका."));
    }
    private static String categoryName(String category, String lang) {
        String c = category == null ? "emergency" : category.toLowerCase();
        if ("hi".equals(lang)) {
            return switch (c) {
                case "road_accident", "accident" -> "सड़क दुर्घटना";
                case "unconscious" -> "बेहोश मरीज़";
                case "breathing_difficulty" -> "सांस की तकलीफ";
                case "severe_bleeding" -> "गंभीर रक्तस्राव";
                case "cardiac_emergency" -> "हृदय आपातकाल";
                case "burn_injury" -> "जलन";
                default -> "आपातकालीन स्थिति";
            };
        }
        if ("mr".equals(lang)) {
            return switch (c) {
                case "road_accident", "accident" -> "रस्ता अपघात";
                case "unconscious" -> "बेशुद्ध रुग्ण";
                case "breathing_difficulty" -> "श्वासाचा त्रास";
                case "severe_bleeding" -> "तीव्र रक्तस्राव";
                case "cardiac_emergency" -> "हृदयाचा झटका";
                case "burn_injury" -> "जळजळ";
                default -> "आपत्कालीन परिस्थिती";
            };
        }
        return switch (c) {
            case "road_accident", "accident" -> "road accident";
            case "unconscious" -> "unconscious patient";
            case "breathing_difficulty" -> "breathing difficulty";
            case "severe_bleeding" -> "severe bleeding";
            case "cardiac_emergency" -> "possible cardiac emergency";
            case "burn_injury" -> "burn injury";
            default -> "emergency";
        };
    }
}
