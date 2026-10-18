package com.resqai.controller;

import com.resqai.dto.ProfileDtos;
import com.resqai.model.EmergencyContactDocument;
import com.resqai.model.EmergencyProfileDocument;
import com.resqai.model.UserDocument;
import com.resqai.repository.EmergencyContactRepository;
import com.resqai.repository.EmergencyProfileRepository;
import com.resqai.repository.UserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Emergency-profile and emergency-contacts endpoints. Every operation is
 * scoped to the authenticated owner derived from the JWT — client-supplied
 * user ids are never trusted, and no user can read or modify another
 * user's medical data.
 */
@RestController
@RequestMapping("/api/v1/users/me")
public class ProfileContactController {

    private final UserRepository users;
    private final EmergencyProfileRepository profiles;
    private final EmergencyContactRepository contacts;

    public ProfileContactController(UserRepository users,
                                    EmergencyProfileRepository profiles,
                                    EmergencyContactRepository contacts) {
        this.users = users;
        this.profiles = profiles;
        this.contacts = contacts;
    }

    // ---------------- Profile ----------------

    @GetMapping("/emergency-profile")
    public ResponseEntity<ProfileDtos.EmergencyProfileResponse> getProfile(Authentication authentication) {
        UserDocument user = requireUser(authentication);
        EmergencyProfileDocument profile = profiles.findByUserId(user.getId()).orElseGet(() -> {
            EmergencyProfileDocument created = new EmergencyProfileDocument();
            created.setUserId(user.getId());
            return created;
        });
        return ResponseEntity.ok(toProfileResponse(user, profile));
    }

    @PutMapping("/emergency-profile")
    public ResponseEntity<ProfileDtos.EmergencyProfileResponse> putProfile(
        Authentication authentication,
        @jakarta.validation.Valid @RequestBody ProfileDtos.EmergencyProfileRequest request) {
        UserDocument user = requireUser(authentication);
        EmergencyProfileDocument profile = profiles.findByUserId(user.getId()).orElseGet(() -> {
            EmergencyProfileDocument created = new EmergencyProfileDocument();
            created.setUserId(user.getId());
            return created;
        });

        if (request.dateOfBirth != null) profile.setDateOfBirth(request.dateOfBirth.isBlank() ? null : request.dateOfBirth.trim());
        if (request.bloodGroup != null) profile.setBloodGroup(request.bloodGroup.isBlank() ? null : request.bloodGroup.trim());
        if (request.allergies != null) profile.setAllergies(new ArrayList<>(request.allergies));
        if (request.medicalConditions != null) profile.setMedicalConditions(new ArrayList<>(request.medicalConditions));
        if (request.currentMedications != null) profile.setCurrentMedications(new ArrayList<>(request.currentMedications));
        profile.setUpdatedAt(Instant.now());
        profiles.save(profile);

        return ResponseEntity.ok(toProfileResponse(user, profile));
    }

    // ---------------- Contacts ----------------

    @GetMapping("/emergency-contacts")
    public ResponseEntity<ProfileDtos.ContactListResponse> getContacts(Authentication authentication) {
        UserDocument user = requireUser(authentication);
        List<ProfileDtos.ContactResponse> list = contacts
            .findByOwnerUserIdOrderByCreatedAtAsc(user.getId()).stream().map(this::toContactResponse).toList();
        ProfileDtos.ContactListResponse response = new ProfileDtos.ContactListResponse();
        response.contacts = list;
        return ResponseEntity.ok(response);
    }

    @PostMapping("/emergency-contacts")
    public ResponseEntity<ProfileDtos.ContactResponse> createContact(
        Authentication authentication,
        @jakarta.validation.Valid @RequestBody ProfileDtos.ContactUpsertRequest request) {
        UserDocument user = requireUser(authentication);
        String contactId = (request.contactId != null && !request.contactId.isBlank())
            ? request.contactId
            : UUID.randomUUID().toString();

        // Idempotent create: replaying the same contactId returns the existing contact.
        EmergencyContactDocument existing = contacts
            .findByContactIdAndOwnerUserId(contactId, user.getId()).orElse(null);
        if (existing != null) {
            return ResponseEntity.status(HttpStatus.OK).body(toContactResponse(existing));
        }

        EmergencyContactDocument contact = new EmergencyContactDocument(
            contactId, user.getId(), request.name.trim(), request.phone.trim(),
            blankToNull(request.relationship), request.primary);
        EmergencyContactDocument saved = contacts.save(contact);
        return ResponseEntity.status(HttpStatus.CREATED).body(toContactResponse(saved));
    }

    @PutMapping("/emergency-contacts/{contactId}")
    public ResponseEntity<ProfileDtos.ContactResponse> updateContact(
        Authentication authentication,
        @PathVariable String contactId,
        @jakarta.validation.Valid @RequestBody ProfileDtos.ContactRequest request) {
        UserDocument user = requireUser(authentication);
        EmergencyContactDocument contact = contacts
            .findByContactIdAndOwnerUserId(contactId, user.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));

        contact.setName(request.name.trim());
        contact.setPhone(request.phone.trim());
        contact.setRelationship(blankToNull(request.relationship));
        contact.setPrimary(request.primary);
        contact.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(toContactResponse(contacts.save(contact)));
    }

    @DeleteMapping("/emergency-contacts/{contactId}")
    public ResponseEntity<Void> deleteContact(Authentication authentication, @PathVariable String contactId) {
        UserDocument user = requireUser(authentication);
        long removed = contacts.deleteByContactIdAndOwnerUserId(contactId, user.getId());
        if (removed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found");
        }
        return ResponseEntity.noContent().build();
    }

    // ---------------- Helpers ----------------

    UserDocument requireUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return users.findByIdAndEnabledTrue(authentication.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    }

    private ProfileDtos.EmergencyProfileResponse toProfileResponse(UserDocument user, EmergencyProfileDocument profile) {
        ProfileDtos.EmergencyProfileResponse response = new ProfileDtos.EmergencyProfileResponse();
        response.dateOfBirth = profile.getDateOfBirth();
        response.bloodGroup = profile.getBloodGroup();
        response.allergies = profile.getAllergies();
        response.medicalConditions = profile.getMedicalConditions();
        response.currentMedications = profile.getCurrentMedications();
        response.infoSource = profile.getInfoSource();
        response.abhaStatus = user.getAbhaStatus();
        response.updatedAt = profile.getUpdatedAt() != null ? profile.getUpdatedAt().toEpochMilli() : null;
        return response;
    }

    private ProfileDtos.ContactResponse toContactResponse(EmergencyContactDocument contact) {
        ProfileDtos.ContactResponse response = new ProfileDtos.ContactResponse();
        response.contactId = contact.getContactId();
        response.name = contact.getName();
        response.phone = contact.getPhone();
        response.relationship = contact.getRelationship();
        response.primary = contact.isPrimary();
        response.createdAt = contact.getCreatedAt() != null ? contact.getCreatedAt().toEpochMilli() : null;
        response.updatedAt = contact.getUpdatedAt() != null ? contact.getUpdatedAt().toEpochMilli() : null;
        return response;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
