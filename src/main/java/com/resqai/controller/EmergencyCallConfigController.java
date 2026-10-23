package com.resqai.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public emergency-call configuration for the app. The app caches this value
 * locally so the SOS call destination stays usable offline; the server stays
 * the authoritative source when online.
 */
@RestController
@RequestMapping("/api/v1/config")
public class EmergencyCallConfigController {

    @Value("${resqai.emergency-call.destination:}")
    private String destination;

    @GetMapping("/emergency-call")
    public ResponseEntity<Map<String, String>> emergencyCall() {
        String value = destination == null ? "" : destination.trim();
        if (!value.matches("^\\+?[0-9]{8,15}$")) {
            // Honest error: never serve an invalid destination silently.
            return ResponseEntity.ok(Map.of(
                "destination", "",
                "source", "SERVER_INVALID",
                "error", "Emergency call destination is not configured on the server"));
        }
        return ResponseEntity.ok(Map.of(
            "destination", value,
            "source", "SERVER"));
    }
}
