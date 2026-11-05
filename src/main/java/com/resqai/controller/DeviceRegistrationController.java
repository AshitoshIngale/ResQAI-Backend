package com.resqai.controller;

import com.resqai.model.UserDocument;
import com.resqai.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Registers the Android device's emergency-SMS signing key (spec 4.4):
 * an EC P-256 keypair generated in Android Keystore; the PUBLIC key is
 * uploaded here over an authenticated connection. The private key never
 * leaves the device. The signed SMS webhook validates against this key.
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceRegistrationController {

    private final UserRepository users;

    public DeviceRegistrationController(UserRepository users) {
        this.users = users;
    }

    public static class RegisterRequest {
        public String deviceId;
        /** Base64 X.509 SPKI EC public key. */
        public String publicKey;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(
            Authentication authentication,
            @Valid @RequestBody RegisterRequest request) {
        if (authentication == null || !authentication.isAuthenticated()
            || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        UserDocument user = users.findByIdAndEnabledTrue(authentication.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Authentication required"));
        if (request == null || request.publicKey == null
            || !request.publicKey.startsWith("MFkw")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "publicKey must be a base64 X.509 SPKI EC P-256 key");
        }
        user.setDevicePublicKey(request.publicKey.trim());
        user.setDeviceId(request.deviceId == null ? "" : request.deviceId.trim());
        users.save(user);
        return ResponseEntity.ok(Map.of("status", "REGISTERED"));
    }
}
