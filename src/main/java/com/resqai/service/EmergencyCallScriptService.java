package com.resqai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the spoken emergency-call script segments (English, Hindi, and the
 * regional language resolved from the incident coordinates). Pure and
 * unit-testable; NO invented medical data — every missing field is spoken as
 * unavailable in English and Hindi sections.
 */
@Service
public class EmergencyCallScriptService {

    public record Segment(String languageCode, String text) {}

    /** Category codes -> plain English wording used in the spoken script. */
    private static final Map<String, String> CATEGORY_WORDS = Map.ofEntries(
        Map.entry("ROAD_ACCIDENT", "road accident"),
        Map.entry("UNCONSCIOUS", "unconscious person"),
        Map.entry("BREATHING_DIFFICULTY", "breathing difficulty"),
        Map.entry("SEVERE_BLEEDING", "severe bleeding"),
        Map.entry("CARDIAC_EMERGENCY", "cardiac emergency"),
        Map.entry("BURN_INJURY", "burn injury"),
        Map.entry("FALL_INJURY", "fall or serious injury"),
        Map.entry("OTHER", "emergency"),
        // legacy categories still accepted by the API
        Map.entry("MEDICAL", "medical emergency"),
        Map.entry("ACCIDENT", "road accident"),
        Map.entry("FIRE", "fire emergency"),
        Map.entry("HARDWARE_TRIGGER_SOS", "emergency")
    );

    /** Short regional opening lines (full detail rides on EN + Hindi). */
    private static final Map<String, String> REGIONAL_OPENERS = Map.of(
        "mr-IN", "आपत्कालीन घटना: %s. घटनास्थळ निर्देशांक: %s.",
        "kn-IN", "ತುರ್ತು ಘಟನೆ: %s. ಸ್ಥಳ ನಿರ್ದೇಶಾಂಕ: %s.",
        "gu-IN", "કટોકટી ઘટના: %s. સ્થળ નિર્દેશાંક: %s.",
        "bn-IN", "জরুরি ঘটনা: %s. ঘটনাস্থলের স্থানাঙ্ক: %s.",
        "ta-IN", "அவசர நிகழ்வு: %s. இடத்தின் ஆள்கூறுகள்: %s.",
        "te-IN", "అత్యవసర సంఘటన: %s. సంఘటనా స్థల నిర్దేశాంకాలు: %s.",
        "ml-IN", "അത്യാഹിത സംഭവം: %s. സംഭവസ്ഥല കോർഡിനേറ്റുകൾ: %s.",
        "pa-IN", "ਐਮਰਜੈਂਸੀ ਘਟਨਾ: %s. ਘਟਨਾ ਸਥਾਨ ਗੁਣਕ: %s."
    );

    /**
     * Builds the ordered script: English, Hindi, then the regional language.
     * English and Hindi both carry the FULL facts; Marathi (the primary demo
     * region) is also fully detailed. Other regional languages get a short
     * opener because EN + HI already carry every fact. The location line is a
     * reverse-geocoded PLACE NAME (area, city, pincode) - humans cannot act
     * on raw coordinates - and it is REPEATED in every detailed language.
     */
    public List<Segment> buildScript(String category, String placeName,
                                     Double lat, Double lon,
                                     String victimName, String bloodGroup,
                                     List<String> allergies, List<String> conditions,
                                     StateLanguageResolver.Resolution regional) {
        String categoryWord = CATEGORY_WORDS.getOrDefault(category, "emergency");
        String locationLine = locationWords(placeName, lat, lon);
        String name = orUnavailable(victimName);

        List<String> en = new ArrayList<>();
        en.add("ResQAI automated emergency alert.");
        en.add("Bystander reports " + categoryWord + ".");
        en.add("Victim: " + name + ".");
        en.add("Blood group: " + orUnavailable(bloodGroup) + ".");
        en.add("Allergies: " + listOrUnavailable(allergies) + ".");
        en.add("Conditions: " + listOrUnavailable(conditions) + ".");
        en.add("Location: " + locationLine + ".");
        en.add("Location again: " + locationLine + ".");
        en.add("Please respond immediately.");

        // Hindi: FULL detail mirroring English, location repeated.
        List<String> hi = new ArrayList<>();
        hi.add("ResQAI आपातकालीन अलर्ट।");
        hi.add("एक द्रष्टा ने " + hiCategory(category) + " की सूचना दी है।");
        hi.add("पीड़ित: " + name + ".");
        hi.add("रक्त समूह: " + orUnavailable(bloodGroup) + ".");
        hi.add("एलर्जी: " + listOrUnavailable(allergies) + ".");
        hi.add("मेडिकल स्थितियाँ: " + listOrUnavailable(conditions) + ".");
        hi.add("स्थान: " + locationLine + ".");
        hi.add("स्थान दोहराया जा रहा है: " + locationLine + ".");
        hi.add("कृपया तुरंत प्रतिक्रिया दें।");

        List<Segment> out = new ArrayList<>();
        out.add(new Segment("en-IN", String.join(" ", en)));
        out.add(new Segment("hi-IN", String.join(" ", hi)));

        if (regional == null) return out;
        if ("mr-IN".equals(regional.languageCode())) {
            // Marathi: FULL detail mirroring English and Hindi.
            List<String> mr = new ArrayList<>();
            mr.add("ResQAI आपत्कालीन इशारा.");
            mr.add("एका द्रष्ट्याने " + mrCategory(category) + " यांची नोंद केली आहे.");
            mr.add("व्यक्ती: " + name + ".");
            mr.add("रक्तगट: " + orUnavailable(bloodGroup) + ".");
            mr.add("ऍलर्जी: " + listOrUnavailable(allergies) + ".");
            mr.add("आरोग्याच्या स्थिती: " + listOrUnavailable(conditions) + ".");
            mr.add("घटनास्थळ: " + locationLine + ".");
            mr.add("स्थान पुन्हा सांगत आहे: " + locationLine + ".");
            mr.add("कृपया लगेच प्रतिसाद द्या.");
            out.add(new Segment("mr-IN", String.join(" ", mr)));
        } else if (!"hi-IN".equals(regional.languageCode())
            && REGIONAL_OPENERS.containsKey(regional.languageCode())) {
            // Other regional languages: short opener (EN + HI carry all facts).
            String text = String.format(
                REGIONAL_OPENERS.get(regional.languageCode()), categoryWord, locationLine);
            out.add(new Segment(regional.languageCode(), text));
        }
        return out;
    }

    private static String mrCategory(String category) {
        return switch (category == null ? "OTHER" : category) {
            case "ROAD_ACCIDENT", "ACCIDENT" -> "रस्ता अपघात";
            case "UNCONSCIOUS" -> "बेशुद्ध व्यक्ती";
            case "BREATHING_DIFFICULTY" -> "श्वास घेण्यास त्रास";
            case "SEVERE_BLEEDING" -> "तीव्र रक्तस्राव";
            case "CARDIAC_EMERGENCY" -> "हृदयविकाराचा झटका";
            case "BURN_INJURY" -> "जळजळ";
            case "FALL_INJURY" -> "पडणे किंवा दुखापत";
            case "FIRE" -> "आग";
            default -> "आपत्कालीन परिस्थिती";
        };
    }

    private static String hiCategory(String category) {
        return switch (category == null ? "OTHER" : category) {
            case "ROAD_ACCIDENT", "ACCIDENT" -> "सड़क दुर्घटना";
            case "UNCONSCIOUS" -> "बेहोश व्यक्ति";
            case "BREATHING_DIFFICULTY" -> "सांस लेने में कठिनाई";
            case "SEVERE_BLEEDING" -> "गंभीर रक्तस्राव";
            case "CARDIAC_EMERGENCY" -> "हृदय आपातकाल";
            case "BURN_INJURY" -> "जलने की चोट";
            case "FALL_INJURY" -> "गिरने या गंभीर चोट";
            case "FIRE" -> "आग";
            default -> "आपातकालीन स्थिति";
        };
    }

    private static String locationWords(String placeName, Double lat, Double lon) {
        // A place name people can act on; coordinates only as last resort.
        if (placeName != null && !placeName.isBlank()) {
            return placeName.trim();
        }
        if (lat == null || lon == null) {
            return "location unavailable";
        }
        return String.format("near coordinates %.3f, %.3f", lat, lon);
    }

    private static String orUnavailable(String value) {
        return (value == null || value.isBlank()) ? "not available" : value;
    }

    private static String listOrUnavailable(List<String> values) {
        if (values == null || values.isEmpty()) return "not available";
        List<String> clean = values.stream()
            .filter(v -> v != null && !v.isBlank()).toList();
        if (clean.isEmpty()) return "not available";
        return String.join(", ", clean);
    }
}
