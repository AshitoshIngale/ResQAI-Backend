package com.resqai.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps incident coordinates (from the BYSTANDER's live location) to the
 * primary regional language of the Indian state those coordinates fall in.
 * Used to pick the third language of the emergency call script alongside
 * English and Hindi.
 *
 * Design (deliberate and testable):
 *  - Coarse "core area" boxes only — no geocoding API, no network, fully
 *    deterministic. Boxes are drawn SAFELY INSIDE each state, so border
 *    regions fall in no box (uncertain) rather than the wrong state.
 *  - A state may own several boxes (e.g. Maharashtra's south/west/north).
 *  - Resolution rule: if every box containing the point maps to the SAME
 *    language, that language wins; zero hits or mixed languages -> UNCERTAIN
 *    (null) and the call speaks English + Hindi only — never a guess.
 *  - Multilingual states use their primary official language; English and
 *    Hindi are always spoken anyway, so nothing is lost.
 */
public final class StateLanguageResolver {

    /** One core-area box; several boxes may share a state/language. */
    public record Box(String state, String languageCode, String languageName,
                      double minLat, double maxLat, double minLon, double maxLon) {}

    private static final List<Box> BOXES = List.of(
        // Maharashtra / Marathi (three core areas)
        new Box("Maharashtra", "mr-IN", "Marathi", 15.8, 19.4, 73.4, 75.2),   // south: Kolhapur, Sangli
        new Box("Maharashtra", "mr-IN", "Marathi", 17.5, 20.0, 72.6, 73.5),   // west: Mumbai, Konkan
        new Box("Maharashtra", "mr-IN", "Marathi", 19.4, 22.0, 73.6, 80.5),   // central/north/Vidarbha: Pune, Nagpur
        // Karnataka / Kannada
        new Box("Karnataka", "kn-IN", "Kannada", 11.8, 15.5, 74.5, 78.4),    // Bengaluru, Mysuru, Hubballi
        // Gujarat / Gujarati
        new Box("Gujarat", "gu-IN", "Gujarati", 20.5, 23.5, 70.0, 73.5),     // Ahmedabad, Surat, Rajkot
        // West Bengal / Bengali
        new Box("West Bengal", "bn-IN", "Bengali", 21.6, 26.6, 87.0, 89.6),  // Kolkata
        // Tamil Nadu / Tamil
        new Box("Tamil Nadu", "ta-IN", "Tamil", 8.2, 13.2, 77.6, 80.3),      // Chennai, Madurai
        // Telangana / Telugu
        new Box("Telangana", "te-IN", "Telugu", 16.5, 19.6, 77.6, 81.3),     // Hyderabad
        // Andhra Pradesh / Telugu
        new Box("Andhra Pradesh", "te-IN", "Telugu", 13.4, 19.1, 77.8, 84.6),// Vijayawada, Visakhapatnam
        // Kerala / Malayalam
        new Box("Kerala", "ml-IN", "Malayalam", 8.4, 12.6, 75.0, 77.2),      // Thiruvananthapuram, Kochi
        // Punjab / Punjabi
        new Box("Punjab", "pa-IN", "Punjabi", 30.2, 32.3, 74.3, 76.3),       // Ludhiana, Amritsar
        // Uttar Pradesh / Hindi (primary already)
        new Box("Uttar Pradesh", "hi-IN", "Hindi", 24.5, 28.3, 77.5, 84.0),  // Lucknow, Kanpur
        // Delhi / Hindi
        new Box("Delhi", "hi-IN", "Hindi", 28.4, 28.9, 76.8, 77.4),
        // Rajasthan / Hindi
        new Box("Rajasthan", "hi-IN", "Hindi", 24.5, 29.0, 70.2, 76.2)       // Jaipur, Jodhpur
    );

    public record Resolution(String state, String languageCode, String languageName, boolean certain) {}

    /**
     * Resolves coordinates to a regional language. Returns null when the
     * coordinates are missing, outside covered core areas, or in a mixed
     * overlap — never fabricates a mapping.
     */
    public static Resolution resolve(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) return null;
        List<Box> hits = BOXES.stream()
            .filter(b -> latitude >= b.minLat() && latitude <= b.maxLat()
                && longitude >= b.minLon() && longitude <= b.maxLon())
            .toList();
        if (hits.isEmpty()) return null;
        Set<String> languages = new LinkedHashSet<>(
            hits.stream().map(Box::languageCode).toList());
        if (languages.size() != 1) return null; // mixed overlap -> uncertain
        Box box = hits.get(0);
        return new Resolution(box.state(), box.languageCode(), box.languageName(), true);
    }

    private StateLanguageResolver() {}
}
