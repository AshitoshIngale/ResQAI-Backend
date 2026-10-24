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
     * Builds the ordered script. English first (fullest detail), then Hindi,
     * then the regional opener when the state mapping is certain. The
     * location line repeats before the summary as required.
     */
    public List<Segment> buildScript(String category, Double lat, Double lon,
                                     String victimName, String bloodGroup,
                                     List<String> allergies, List<String> conditions,
                                     StateLanguageResolver.Resolution regional) {
        String categoryWord = CATEGORY_WORDS.getOrDefault(category, "emergency");
        String locationLine = locationWords(lat, lon);
        String name = orUnavailable(victimName);

        // CONCISE scripts: spoken audio runs real-time, so the full
        // three-language call stays near 30 seconds. English carries every
        // authorized fact; Hindi is the compact alert; the regional opener
        // is one line. Missing fields stay honestly absent.
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

        List<String> hi = new ArrayList<>();
        hi.add("ResQAI आपातकालीन अलर्ट।");
        hi.add(hiCategory(category) + " की सूचना है।");
        hi.add("पीड़ित: " + name + ".");
        hi.add("स्थान: " + locationLine + ".");
        hi.add("कृपया तुरंत प्रतिक्रिया दें।");

        List<Segment> out = new ArrayList<>();
        out.add(new Segment("en-IN", String.join(" ", en)));
        out.add(new Segment("hi-IN", String.join(" ", hi)));
        if (regional != null && !"hi-IN".equals(regional.languageCode())
            && REGIONAL_OPENERS.containsKey(regional.languageCode())) {
            String coords = (lat != null && lon != null)
                ? lat + ", " + lon : "unavailable";
            String text = String.format(
                REGIONAL_OPENERS.get(regional.languageCode()), categoryWord, coords);
            out.add(new Segment(regional.languageCode(), text));
        }
        return out;
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

    private static String locationWords(Double lat, Double lon) {
        if (lat == null || lon == null) {
            return "coordinates unavailable";
        }
        // 3 decimals ~= 100 m precision; fewer spoken digits, shorter audio.
        return String.format("%.3f, %.3f", lat, lon);
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
