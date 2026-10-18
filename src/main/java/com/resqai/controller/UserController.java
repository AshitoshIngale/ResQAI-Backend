package com.resqai.controller;

import com.resqai.model.ContactView;
import com.resqai.model.EmergencyContactDocument;
import com.resqai.model.EmergencyProfileDocument;
import com.resqai.model.UserDocument;
import com.resqai.repository.EmergencyContactRepository;
import com.resqai.repository.EmergencyProfileRepository;
import com.resqai.repository.UserRepository;

import com.resqai.dto.AuthDtos;
import com.resqai.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

/**
 * Authorized user endpoints. The caller's identity ALWAYS comes from the JWT
 * (server-side), never from a client-supplied id — a user can only read or
 * update their own emergency profile.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserRepository users;
    private final EmergencyProfileRepository profiles;
    private final EmergencyContactRepository contacts;
    private final AuthService authService;

    public UserController(UserRepository users, EmergencyProfileRepository profiles,
                          EmergencyContactRepository contacts, AuthService authService) {
        this.users = users;
        this.profiles = profiles;
        this.contacts = contacts;
        this.authService = authService;
    }

    @GetMapping("/me")
    public ResponseEntity<AuthDtos.UserMeResponse> me(Authentication authentication) {
        UserDocument user = requireUser(authentication);
        EmergencyProfileDocument profile = profiles.findByUserId(user.getId()).orElse(null);
        List<EmergencyProfileDocument.Contact> embedded =
            profile != null ? profile.getEmergencyContacts() : List.<EmergencyProfileDocument.Contact>of();
        List<ContactView> contactViews = new ArrayList<>(embedded);
        return ResponseEntity.ok(authService.toUserMe(user, profile, contactViews));
    }

    @PatchMapping("/me/emergency-profile")
    public ResponseEntity<AuthDtos.UserMeResponse> updateEmergencyProfile(
        Authentication authentication,
        @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody AuthDtos.UpdateEmergencyProfileRequest request) {
        UserDocument user = requireUser(authentication);

        EmergencyProfileDocument profile = profiles.findByUserId(user.getId())
            .orElseGet(() -> {
                EmergencyProfileDocument created = new EmergencyProfileDocument();
                created.setUserId(user.getId());
                return created;
            });

        if (request.dateOfBirth != null) profile.setDateOfBirth(request.dateOfBirth.isBlank() ? null : request.dateOfBirth.trim());
        if (request.bloodGroup != null) profile.setBloodGroup(request.bloodGroup.isBlank() ? null : request.bloodGroup.trim());
        if (request.allergies != null) profile.setAllergies(new ArrayList<>(request.allergies));
        if (request.medicalConditions != null) profile.setMedicalConditions(new ArrayList<>(request.medicalConditions));
        if (request.currentMedications != null) profile.setCurrentMedications(new ArrayList<>(request.currentMedications));
        if (request.emergencyContacts != null) {
            // Phase 3: contacts live in their own collection now. Registrations
            // seeded them into the profile document; PATCH writes update the
            // embedded seed only when the collection is empty for this owner.
            if (contacts.findByOwnerUserIdOrderByCreatedAtAsc(user.getId()).isEmpty()) {
                request.emergencyContacts.forEach(c -> contacts.save(new EmergencyContactDocument(
                    java.util.UUID.randomUUID().toString(),
                    user.getId(),
                    c.name.trim(),
                    c.phone.trim(),
                    c.relationship,
                    false)));
            }
        }
        profile.setUpdatedAt(Instant.now());
        profiles.save(profile);

        return ResponseEntity.ok(authService.toUserMe(user, profile, List.of()));
    }

    private UserDocument requireUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByIdAndEnabledTrue(authentication.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    }
}
