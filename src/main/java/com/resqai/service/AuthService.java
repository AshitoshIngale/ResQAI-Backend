package com.resqai.service;

import com.resqai.dto.AuthDtos;

import com.resqai.security.Argon2idPasswordEncoder;
import com.resqai.security.JwtService;
import com.resqai.security.RateLimiter;
import com.resqai.model.ContactView;
import com.resqai.model.EmergencyContactDocument;
import com.resqai.repository.EmergencyContactRepository;
import com.resqai.model.EmergencyProfileDocument;
import com.resqai.repository.EmergencyProfileRepository;
import com.resqai.model.UserDocument;
import com.resqai.repository.UserRepository;
import com.resqai.model.PendingVerification;
import com.resqai.exception.VerificationException;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Authentication business logic. Security properties enforced here:
 *  - Passwords hashed with Argon2id, never logged.
 *  - Verification codes/OTPs hashed at rest, expiring, attempt-bounded.
 *  - Generic login errors (no account enumeration).
 *  - Refresh-token rotation with a single active session per user.
 *  - Registration is not complete until email AND phone are verified.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String[] DISPOSABLE_EMAIL_DOMAINS = {
        "mailinator.com", "10minutemail.com", "guerrillamail.com", "tempmail.com",
        "yopmail.com", "throwawaymail.com", "getnada.com", "dispostable.com",
        "trashmail.com", "sharklasers.com", "fakeinbox.com", "maildrop.cc"
    };

    private final UserRepository users;
    private final EmergencyProfileRepository profiles;
    private final EmergencyContactRepository emergencyContacts;
    private final Argon2idPasswordEncoder encoder;
    private final JwtService jwtService;
    private final VerificationService verifications;
    private final EmailSender emailSender;
    private final SmsOtpSender smsSender;
    private final RateLimiter rateLimiter;

    private final long emailTtlMinutes;
    private final long otpTtlMinutes;
    private final int otpMaxAttempts;
    private final long resetTtlMinutes;

    public AuthService(
        UserRepository users,
        EmergencyProfileRepository profiles,
        EmergencyContactRepository emergencyContacts,
        Argon2idPasswordEncoder encoder,
        JwtService jwtService,
        VerificationService verifications,
        EmailSender emailSender,
        SmsOtpSender smsSender,
        RateLimiter rateLimiter,
        @Value("${resqai.email-verification-ttl-minutes:30}") long emailTtlMinutes,
        @Value("${resqai.phone-otp-ttl-minutes:10}") long otpTtlMinutes,
        @Value("${resqai.phone-otp-max-attempts:5}") int otpMaxAttempts,
        @Value("${resqai.reset-token-ttl-minutes:30}") long resetTtlMinutes
    ) {
        this.users = users;
        this.profiles = profiles;
        this.emergencyContacts = emergencyContacts;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.verifications = verifications;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
        this.rateLimiter = rateLimiter;
        this.emailTtlMinutes = emailTtlMinutes;
        this.otpTtlMinutes = otpTtlMinutes;
        this.otpMaxAttempts = otpMaxAttempts;
        this.resetTtlMinutes = resetTtlMinutes;
    }

    // ---------------- Registration ----------------

    public AuthDtos.RegisterResponse register(AuthDtos.RegisterRequest request) {
        String normalizedEmail = normalizeEmail(request.email);
        String normalizedPhone = normalizePhone(request.phone);

        if (isDisposableDomain(normalizedEmail)) {
            // Same message as a taken email — no enumeration of the disposable policy either.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Registration failed");
        }

        // Fast pre-check (the unique index remains the authoritative guard under concurrency)
        if (users.existsByNormalizedEmail(normalizedEmail) || users.existsByNormalizedPhone(normalizedPhone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Registration failed");
        }

        UserDocument user = new UserDocument();
        user.setId(UUID.randomUUID().toString());
        user.setFullName(request.fullName.trim());
        user.setEmail(request.email.trim());
        user.setNormalizedEmail(normalizedEmail);
        user.setPhone(request.phone.trim());
        user.setNormalizedPhone(normalizedPhone);
        user.setPasswordHash(encoder.encode(request.password));
        user.setAcceptedTermsAt(request.acceptedTermsAt);
        user.setPreferredLanguage(request.preferredLanguage == null ? "en" : request.preferredLanguage);
        user.setAbhaStatus(Boolean.TRUE.equals(request.abhaIntended) ? "LINK_PENDING" : "NOT_LINKED");
        user.setEmergencyId(generateEmergencyId());

        try {
            users.save(user);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Registration failed");
        }

        // Emergency profile seed (Path A or B share the same storage; source differs)
        EmergencyProfileDocument profile = new EmergencyProfileDocument();
        profile.setUserId(user.getId());
        profile.setDateOfBirth(blankToNull(request.dateOfBirth));
        profile.setBloodGroup(blankToNull(request.bloodGroup));
        profile.setInfoSource(Boolean.TRUE.equals(request.abhaIntended) ? "ABHA_PENDING" : "USER_PROVIDED");
        if (request.emergencyContactName != null && !request.emergencyContactName.isBlank()) {
            profile.getEmergencyContacts().add(new EmergencyProfileDocument.Contact(
                request.emergencyContactName.trim(), request.emergencyContactPhone.trim(), null));
        }
        profiles.save(profile);

        String emailCode = numericCode(6);
        verifications.issue(PendingVerification.Kind.EMAIL_VERIFICATION, normalizedEmail, emailCode, emailTtlMinutes, 10);
        emailSender.sendVerificationCode(request.email.trim(), emailCode);

        String message = emailSender.isProduction()
            ? "Registration started. Verify your email and phone to activate the account."
            : "TEST MODE: no email provider configured — the verification code is in the server log only.";

        audit(user, "REGISTER", emailSender.isProduction() ? "smtp" : "dev-mail-adapter");
        users.save(user);

        log.info("User registered: {} (emailVerified={}, phoneVerified={})", user.getId(), false, false);
        return new AuthDtos.RegisterResponse(user.getId(), true, true, message);
    }

    // ---------------- Email verification ----------------

    public void verifyEmail(AuthDtos.VerifyEmailRequest request) {
        String normalizedEmail = normalizeEmail(request.email);
        try {
            verifications.consume(PendingVerification.Kind.EMAIL_VERIFICATION, normalizedEmail, request.token.trim());
        } catch (VerificationException.TooManyAttempts e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts", e);
        } catch (VerificationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification code", e);
        }

        UserDocument user = users.findByNormalizedEmail(normalizedEmail)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification code"));
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(Instant.now());
            audit(user, "VERIFY_EMAIL", null);
            users.save(user);
        }
    }

    // ---------------- Phone verification ----------------

    public AuthDtos.MessageResponse sendPhoneOtp(AuthDtos.SendPhoneOtpRequest request, String clientKey) {
        if (!rateLimiter.tryConsume(RateLimiter.Policy.PHONE_OTP, clientKey + ":" + request.phone)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many OTP requests");
        }
        String normalizedPhone = normalizePhone(request.phone);
        users.findByNormalizedPhone(normalizedPhone)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification code"));

        String otp = numericCode(6);
        verifications.issue(PendingVerification.Kind.PHONE_OTP, normalizedPhone, otp, otpTtlMinutes, otpMaxAttempts);
        smsSender.sendOtp(request.phone.trim(), otp);

        String message = smsSender.isProduction()
            ? "OTP sent." + (otpMaxAttempts > 0 ? "" : "")
            : "TEST MODE: no SMS provider configured — the OTP is in the server log only.";
        return new AuthDtos.MessageResponse(message);
    }

    public void verifyPhoneOtp(AuthDtos.VerifyPhoneOtpRequest request) {
        String normalizedPhone = normalizePhone(request.phone);
        try {
            verifications.consume(PendingVerification.Kind.PHONE_OTP, normalizedPhone, request.otp.trim());
        } catch (VerificationException.TooManyAttempts e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts", e);
        } catch (VerificationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification code", e);
        }

        UserDocument user = users.findByNormalizedPhone(normalizedPhone)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification code"));
        if (!user.isPhoneVerified()) {
            user.setPhoneVerified(true);
            user.setPhoneVerifiedAt(Instant.now());
            audit(user, "VERIFY_PHONE", null);
            users.save(user);
        }
    }

    // ---------------- Login / sessions ----------------

    public AuthDtos.AuthTokensResponse login(AuthDtos.LoginRequest request, String clientKey) {
        if (!rateLimiter.tryConsume(RateLimiter.Policy.LOGIN, clientKey)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts");
        }

        String identifier = request.identifier.trim();
        UserDocument user = findByIdentifier(identifier);

        // Generic failure for both unknown identifier and wrong password.
        if (user == null || !encoder.matches(request.password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect credentials");
        }
        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect credentials");
        }

        // Verification gates: login requires a verified contact method.
        if (!user.isEmailVerified() && !user.isPhoneVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verify your email and phone before signing in");
        }
        if (identifier.contains("@") && !user.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verify your email before signing in");
        }
        if (!identifier.contains("@") && !user.isPhoneVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verify your phone before signing in");
        }

        String accessToken = jwtService.createAccessToken(user.getId());
        String refreshToken = jwtService.createRefreshToken(user.getId());
        Claims refreshClaims = jwtService.validateRefreshToken(refreshToken);
        user.setActiveRefreshJti(refreshClaims.getId());
        user.setActiveRefreshExpiresAt(refreshClaims.getExpiration().toInstant());
        audit(user, "LOGIN", null);
        users.save(user);

        return new AuthDtos.AuthTokensResponse(
            accessToken, refreshToken, 900, toUserMe(user, profiles.findByUserId(user.getId()).orElse(null), fetchContacts(user.getId())));
    }

    public AuthDtos.AuthTokensResponse refresh(AuthDtos.RefreshRequest request) {
        Claims claims = jwtService.validateRefreshToken(request.refreshToken);
        if (claims == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
        }
        UserDocument user = users.findById(claims.getSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session"));

        // Rotation: only the single active jti may refresh.
        if (!claims.getId().equals(user.getActiveRefreshJti())
            || user.getActiveRefreshExpiresAt() == null
            || Instant.now().isAfter(user.getActiveRefreshExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
        }

        String accessToken = jwtService.createAccessToken(user.getId());
        String refreshToken = jwtService.createRefreshToken(user.getId());
        Claims newClaims = jwtService.validateRefreshToken(refreshToken);
        user.setActiveRefreshJti(newClaims.getId());
        user.setActiveRefreshExpiresAt(newClaims.getExpiration().toInstant());
        users.save(user);

        return new AuthDtos.AuthTokensResponse(
            accessToken, refreshToken, 900, toUserMe(user, profiles.findByUserId(user.getId()).orElse(null), fetchContacts(user.getId())));
    }

    public void logout(AuthDtos.LogoutRequest request) {
        Claims claims = jwtService.validateRefreshToken(request.refreshToken);
        if (claims == null) return; // already invalid — nothing to revoke
        users.findById(claims.getSubject()).ifPresent(user -> {
            if (claims.getId().equals(user.getActiveRefreshJti())) {
                user.setActiveRefreshJti(null);
                user.setActiveRefreshExpiresAt(null);
                audit(user, "LOGOUT", null);
                users.save(user);
            }
        });
    }

    // ---------------- Password recovery ----------------

    public void forgotPassword(AuthDtos.ForgotPasswordRequest request, String clientKey) {
        if (!rateLimiter.tryConsume(RateLimiter.Policy.PASSWORD_RESET, clientKey)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
        }
        // Generic response regardless of account existence (no enumeration).
        String normalizedEmail = normalizeEmail(request.email);
        users.findByNormalizedEmail(normalizedEmail).ifPresent(user -> {
            String code = numericCode(6);
            verifications.issue(PendingVerification.Kind.PASSWORD_RESET, normalizedEmail, code, resetTtlMinutes, 10);
            emailSender.sendPasswordResetCode(user.getEmail(), code);
            audit(user, "PASSWORD_RESET_REQUESTED", emailSender.isProduction() ? "smtp" : "dev-mail-adapter");
            users.save(user);
        });
    }

    public void resetPassword(AuthDtos.ResetPasswordRequest request) {
        String normalizedEmail = normalizeEmail(request.email);
        try {
            verifications.consume(PendingVerification.Kind.PASSWORD_RESET, normalizedEmail, request.resetToken.trim());
        } catch (VerificationException.TooManyAttempts e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts", e);
        } catch (VerificationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset code", e);
        }

        UserDocument user = users.findByNormalizedEmail(normalizedEmail)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset code"));
        user.setPasswordHash(encoder.encode(request.newPassword));
        // Revoke any active session: the user must sign in again everywhere.
        user.setActiveRefreshJti(null);
        user.setActiveRefreshExpiresAt(null);
        audit(user, "PASSWORD_RESET_COMPLETED", null);
        users.save(user);
    }

    // ---------------- Helpers ----------------

    public boolean isEmailProviderProduction() {
        return emailSender.isProduction();
    }

    public boolean isSmsProviderProduction() {
        return smsSender.isProduction();
    }

    private UserDocument findByIdentifier(String identifier) {
        if (identifier.contains("@")) {
            return users.findByNormalizedEmail(normalizeEmail(identifier)).orElse(null);
        }
        try {
            return users.findByNormalizedPhone(normalizePhone(identifier)).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizePhone(String phone) {
        String digits = phone.trim().replaceAll("[\\s\\-()]", "");
        if (!digits.matches("^\\+[1-9][0-9]{7,14}$")) {
            throw new IllegalArgumentException("phone format");
        }
        return digits;
    }

    private boolean isDisposableDomain(String normalizedEmail) {
        String domain = normalizedEmail.substring(normalizedEmail.indexOf('@') + 1);
        for (String disposable : DISPOSABLE_EMAIL_DOMAINS) {
            if (domain.equals(disposable)) return true;
        }
        return false;
    }

    private String numericCode(int digits) {
        int bound = (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", ThreadLocalRandom.current().nextInt(bound));
    }

    private String generateEmergencyId() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        return "RQ-" + suffix;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private void audit(UserDocument user, String action, String detail) {
        user.getAuditEvents().add(new UserDocument.AuditEvent(action, detail));
        user.setUpdatedAt(Instant.now());
    }

    public AuthDtos.UserMeResponse toUserMe(UserDocument user, EmergencyProfileDocument profile) {
        ensureEmergencyCode(user);
        return toUserMe(user, profile, fetchContacts(user.getId()));
    }

    /**
     * Lazily issues the opaque QR/lookup code for accounts created before
     * Phase 4. 128 bits of entropy from SecureRandom — unenumerable.
     */
    public void ensureEmergencyCode(UserDocument user) {
        if (user.getEmergencyCode() == null || user.getEmergencyCode().isBlank()) {
            byte[] raw = new byte[16];
            new SecureRandom().nextBytes(raw);
            user.setEmergencyCode(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
            users.save(user);
        }
    }

    /** Contacts come from the dedicated collection; falls back to the embedded Phase 2 seed. */
    public AuthDtos.UserMeResponse toUserMe(UserDocument user, EmergencyProfileDocument profile,
                                            List<ContactView> contacts) {
        // Lazily issue the QR/lookup code for accounts created before Phase 4.
        ensureEmergencyCode(user);
        AuthDtos.UserMeResponse me = new AuthDtos.UserMeResponse();
        me.userId = user.getId();
        me.fullName = user.getFullName();
        me.email = user.getEmail();
        me.emailVerified = user.isEmailVerified();
        me.phone = user.getPhone();
        me.phoneVerified = user.isPhoneVerified();
        me.emergencyId = user.getEmergencyId();
        me.emergencyCode = user.getEmergencyCode();
        me.abhaStatus = user.getAbhaStatus();
        me.preferredLanguage = user.getPreferredLanguage();
        me.createdAt = user.getCreatedAt().toEpochMilli();
        if (profile != null) {
            AuthDtos.EmergencyProfileDto dto = new AuthDtos.EmergencyProfileDto();
            dto.dateOfBirth = profile.getDateOfBirth();
            dto.bloodGroup = profile.getBloodGroup();
            dto.allergies = profile.getAllergies();
            dto.medicalConditions = profile.getMedicalConditions();
            dto.currentMedications = profile.getCurrentMedications();
            dto.infoSource = profile.getInfoSource();
            if (contacts != null && !contacts.isEmpty()) {
                dto.emergencyContacts = contacts.stream().map(c -> {
                    AuthDtos.EmergencyContactDto out = new AuthDtos.EmergencyContactDto();
                    out.name = c.getName();
                    out.phone = c.getPhone();
                    out.relationship = c.getRelationship();
                    return out;
                }).toList();
            } else {
                dto.emergencyContacts = profile.getEmergencyContacts().stream().map(c -> {
                    AuthDtos.EmergencyContactDto out = new AuthDtos.EmergencyContactDto();
                    out.name = c.getName();
                    out.phone = c.getPhone();
                    out.relationship = c.getRelationship();
                    return out;
                }).toList();
            }
            me.emergencyProfile = dto;
        }
        return me;
    }

    private List<ContactView> fetchContacts(String ownerUserId) {
        return new ArrayList<>(emergencyContacts.findByOwnerUserIdOrderByCreatedAtAsc(ownerUserId));
    }
}
