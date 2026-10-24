package com.resqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Turns incident coordinates into a human-readable place name + pincode for
 * the spoken emergency script (people cannot act on raw coordinates).
 * Uses OpenStreetMap's Nominatim reverse geocoding (no API key; usage policy:
 * identified User-Agent, low volume). Best-effort: any failure returns null
 * and the script falls back to spoken coordinates — never a fabricated place.
 */
@Service
public class ReverseGeocodeService {

    private static final Logger log = LoggerFactory.getLogger(ReverseGeocodeService.class);
    private static final String NOMINATIM = "https://nominatim.openstreetmap.org/reverse";
    private static final String USER_AGENT = "ResQAI-Emergency-Prototype/1.0 (student project)";

    private final ProviderHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Rounded-coordinate cache: reverse results rarely differ within ~100 m. */
    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    public ReverseGeocodeService(ProviderHttpClient http) {
        this.http = http;
    }

    /**
     * Returns a compact address like "Rajarampuri, Kolhapur, Maharashtra 416013",
     * or null when lookup fails. Best available parts only — nothing invented.
     */
    public String resolvePlaceName(double latitude, double longitude) {
        String cacheKey = String.format("%.3f,%.3f", latitude, longitude);
        String cached = cache.get(cacheKey);
        if (cached != null) return cached.isEmpty() ? null : cached;
        try {
            String url = NOMINATIM
                + "?lat=" + URLEncoder.encode(String.valueOf(latitude), StandardCharsets.UTF_8)
                + "&lon=" + URLEncoder.encode(String.valueOf(longitude), StandardCharsets.UTF_8)
                + "&format=json&zoom=16&addressdetails=1&accept-language=en";
            ProviderHttpClient.ProviderResponse response = http.sendWithUserAgent(
                "GET", url, "application/json", null, USER_AGENT, new byte[0]);
            if (response.statusCode < 200 || response.statusCode >= 300) {
                cache.put(cacheKey, "");
                return null;
            }
            JsonNode json = mapper.readTree(response.body);
            JsonNode address = json.path("address");
            String area = firstNonBlank(
                address.path("neighbourhood").asText(null),
                address.path("suburb").asText(null),
                address.path("village").asText(null),
                address.path("town").asText(null),
                address.path("city_district").asText(null));
            String city = firstNonBlank(
                address.path("city").asText(null),
                address.path("town").asText(null),
                address.path("village").asText(null));
            String state = address.path("state").asText(null);
            String postcode = address.path("postcode").asText(null);

            StringBuilder out = new StringBuilder();
            if (area != null && (city == null || !area.equals(city))) out.append(area).append(", ");
            if (city != null) out.append(city).append(", ");
            if (state != null) out.append(state);
            if (postcode != null) out.append(" ").append(postcode);

            String result = out.toString().trim().replaceAll(", $", "");
            if (result.isBlank()) {
                cache.put(cacheKey, "");
                return null;
            }
            if (cache.size() > 500) cache.clear();
            cache.put(cacheKey, result);
            log.info("reverse geocode {} -> {}", cacheKey, result);
            return result;
        } catch (Exception e) {
            // Network/timeout/policy: coordinates remain the honest fallback.
            log.warn("reverse geocode failed: {}", e.getMessage());
            cache.put(cacheKey, "");
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
