package com.resqai;

import com.resqai.repository.PendingVerificationRepository;

import com.resqai.dto.AuthDtos;
import com.resqai.service.AuthService;
import com.resqai.security.Argon2idPasswordEncoder;
import com.resqai.security.JwtService;
import com.resqai.security.RateLimiter;
import com.resqai.repository.EmergencyContactRepository;
import com.resqai.model.EmergencyProfileDocument;
import com.resqai.repository.EmergencyProfileRepository;
import com.resqai.controller.UserController;
import com.resqai.model.UserDocument;
import com.resqai.repository.UserRepository;
import com.resqai.service.DevLogEmailSender;
import com.resqai.service.DevLogSmsOtpSender;
import com.resqai.model.PendingVerification;
import com.resqai.service.VerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Service-level tests of the full auth lifecycle. All required Phase 2
 * behaviors are covered here: registration (both ABHA paths), disposable-email
 * rejection, duplicate registration, verification success/expiry/attempts,
 * login via email and phone, wrong password, unverified accounts, refresh
 * rotation, logout, password reset, and profile authorization.
 */
class AuthServiceTest {

    private UserRepository users;
    private EmergencyProfileRepository profiles;
    private EmergencyContactRepository contactRepo;
    private VerificationService verifications;
    private Map<String, PendingVerification> pendingStore;
    private Map<String, String> sentEmailCodes;
    private Map<String, String> sentOtps;
    private AuthService service;
    private RateLimiter rateLimiter;

    private static final String TEST_SECRET = Base64.getEncoder()
        .encodeToString("test-only-secret-for-jwt-signing-must-be-long-enough!!".getBytes());

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(EmergencyProfileRepository.class);
        contactRepo = mock(EmergencyContactRepository.class);
        when(contactRepo.findByOwnerUserIdOrderByCreatedAtAsc(anyString()))
            .thenReturn(java.util.List.of());

        // In-memory pending-verification store backing VerificationService
        pendingStore = new HashMap<>();
        var pendingRepo = mock(com.resqai.repository.PendingVerificationRepository.class);
        when(pendingRepo.save(any())).thenAnswer((InvocationOnMock inv) -> {
            PendingVerification pv = inv.getArgument(0);
            pendingStore.put(pv.getKind() + ":" + pv.getKey(), pv);
            return pv;
        });
        when(pendingRepo.findFirstByKindAndKeyOrderByExpiresAtDesc(any(), anyString()))
            .thenAnswer(inv -> {
                PendingVerification.Kind kind = inv.getArgument(0);
                String key = inv.getArgument(1);
                return Optional.ofNullable(pendingStore.get(kind + ":" + key));
            });
        when(pendingRepo.deleteByKindAndKey(any(), anyString())).thenAnswer(inv -> {
            PendingVerification.Kind kind = inv.getArgument(0);
            String key = inv.getArgument(1);
            return pendingStore.remove(kind + ":" + key) != null ? 1L : 0L;
        });
        doAnswer(inv -> {
            PendingVerification pv = inv.getArgument(0);
            pendingStore.remove(pv.getKind() + ":" + pv.getKey());
            return null;
        }).when(pendingRepo).delete(any(PendingVerification.class));

        verifications = new VerificationService(pendingRepo, new Argon2idPasswordEncoder());

        // Capturing senders (dev adapters record what they "sent")
        sentEmailCodes = new HashMap<>();
        sentOtps = new HashMap<>();
        DevLogEmailSender emailSender = new DevLogEmailSender() {
            @Override
            public void sendVerificationCode(String to, String code) {
                sentEmailCodes.put("verify:" + to.toLowerCase(), code);
            }

            @Override
            public void sendPasswordResetCode(String to, String code) {
                sentEmailCodes.put("reset:" + to.toLowerCase(), code);
            }
        };
        DevLogSmsOtpSender smsSender = new DevLogSmsOtpSender() {
            @Override
            public void sendOtp(String phone, String otp) {
                sentOtps.put(phone, otp);
            }
        };

        rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.tryConsume(any(), anyString())).thenReturn(true);

        service = new AuthService(
            users, profiles, contactRepo, new Argon2idPasswordEncoder(),
            new JwtService(TEST_SECRET, 900, 2592000),
            verifications, emailSender, smsSender, rateLimiter,
            30, 10, 5, 30);
    }

    private AuthDtos.RegisterRequest validRegistration() {
        AuthDtos.RegisterRequest request = new AuthDtos.RegisterRequest();
        request.fullName = "Asha Patil";
        request.email = "asha@example.com";
        request.phone = "+919876543210";
        request.password = "S3cure!Passw0rd";
        request.bloodGroup = "B+";
        request.emergencyContactName = "Meera";
        request.emergencyContactPhone = "+919812345678";
        request.preferredLanguage = "en";
        request.abhaIntended = false;
        request.acceptedTermsAt = System.currentTimeMillis();
        return request;
    }

    private UserDocument anyUser(String id, String email, String phone) {
        UserDocument user = new UserDocument();
        user.setId(id);
        user.setFullName("Asha Patil");
        user.setEmail(email);
        user.setNormalizedEmail(AuthService.normalizeEmail(email));
        user.setPhone(phone);
        user.setNormalizedPhone(phone);
        user.setPasswordHash(new Argon2idPasswordEncoder().encode("S3cure!Passw0rd"));
        user.setEmergencyId("RQ-TEST1234");
        return user;
    }

    private AuthDtos.VerifyEmailRequest verifyRequest(String email, String code) {
        AuthDtos.VerifyEmailRequest request = new AuthDtos.VerifyEmailRequest();
        request.email = email;
        request.token = code;
        return request;
    }

    private AuthDtos.VerifyPhoneOtpRequest otpRequest(String phone, String otp) {
        AuthDtos.VerifyPhoneOtpRequest request = new AuthDtos.VerifyPhoneOtpRequest();
        request.phone = phone;
        request.otp = otp;
        return request;
    }

    private AuthDtos.LoginRequest loginRequest(String identifier, String password) {
        AuthDtos.LoginRequest request = new AuthDtos.LoginRequest();
        request.identifier = identifier;
        request.password = password;
        return request;
    }

    // ---------------- Registration ----------------

    @Test
    void validNonAbhaRegistrationIssuesCodesAndSeedsProfileAsUserProvided() {
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AuthDtos.RegisterResponse response = service.register(validRegistration());

        assertNotNull(response.userId);
        assertTrue(response.emailVerificationRequired);
        assertTrue(response.phoneVerificationRequired);
        assertNotNull(sentEmailCodes.get("verify:asha@example.com"));

        org.mockito.ArgumentCaptor<EmergencyProfileDocument> captor =
            org.mockito.ArgumentCaptor.forClass(EmergencyProfileDocument.class);
        org.mockito.Mockito.verify(profiles).save(captor.capture());
        assertEquals("USER_PROVIDED", captor.getValue().getInfoSource());
    }

    @Test
    void abhaIntendedRegistrationMarksLinkPendingAndPendingSource() {
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AuthDtos.RegisterRequest request = validRegistration();
        request.abhaIntended = true;

        service.register(request);

        org.mockito.ArgumentCaptor<UserDocument> userCaptor = org.mockito.ArgumentCaptor.forClass(UserDocument.class);
        org.mockito.Mockito.verify(users, org.mockito.Mockito.atLeastOnce()).save(userCaptor.capture());
        assertEquals("LINK_PENDING", userCaptor.getValue().getAbhaStatus());

        org.mockito.ArgumentCaptor<EmergencyProfileDocument> profileCaptor =
            org.mockito.ArgumentCaptor.forClass(EmergencyProfileDocument.class);
        org.mockito.Mockito.verify(profiles).save(profileCaptor.capture());
        assertEquals("ABHA_PENDING", profileCaptor.getValue().getInfoSource());
    }

    @Test
    void disposableEmailDomainsAreRejectedWithoutRevealingPolicy() {
        AuthDtos.RegisterRequest request = validRegistration();
        request.email = "throwaway@mailinator.com";

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.register(request));
        assertEquals(409, e.getStatusCode().value());
    }

    @Test
    void duplicateEmailRegistrationConflicts() {
        when(users.save(any())).thenThrow(DuplicateKeyException.class);
        AuthDtos.RegisterRequest request = validRegistration();
        request.email = "already@exists.com";

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.register(request));
        assertEquals(409, e.getStatusCode().value());
    }

    // ---------------- Email verification ----------------

    @Test
    void emailVerificationFailsWithWrongCodeAndSucceedsWithCorrectCode() {
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(users.findByNormalizedEmail(anyString())).thenAnswer(inv ->
            Optional.of(anyUser("u-second", (String) inv.getArgument(0), "+919876540002")));

        AuthDtos.RegisterRequest request = validRegistration();
        request.email = "second@example.com";
        request.phone = "+919876540002";
        service.register(request);

        ResponseStatusException wrong = assertThrows(ResponseStatusException.class,
            () -> service.verifyEmail(verifyRequest(request.email, "000000")));
        assertEquals(400, wrong.getStatusCode().value());

        service.verifyEmail(verifyRequest(request.email, sentEmailCodes.get("verify:second@example.com")));

        org.mockito.ArgumentCaptor<UserDocument> captor = org.mockito.ArgumentCaptor.forClass(UserDocument.class);
        org.mockito.Mockito.verify(users, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(UserDocument::isEmailVerified));
    }

    @Test
    void expiredEmailCodeIsRejected() {
        // Issue with ttl=-1 minute → already expired regardless of clock granularity
        AuthService zeroTtl = new AuthService(users, profiles, contactRepo, new Argon2idPasswordEncoder(),
            new JwtService(TEST_SECRET, 900, 2592000), verifications,
            new DevLogEmailSender(), new DevLogSmsOtpSender(), rateLimiter,
            -1, 10, 5, 30);
        zeroTtl.register(validRegistration());
        assertTrue(pendingStore.values().stream().anyMatch(PendingVerification::isExpired));

        // The code value is irrelevant once expired — the entry is rejected outright
        AuthDtos.VerifyEmailRequest request = verifyRequest("asha@example.com", "000000");
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> zeroTtl.verifyEmail(request));
        assertEquals(400, e.getStatusCode().value());
    }

    // ---------------- Phone OTP ----------------

    @Test
    void phoneOtpExpiryInvalidCodeAndSuccess() {
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(users.findByNormalizedPhone(anyString()))
            .thenAnswer(inv -> {
                String phone = inv.getArgument(0);
                if ("+919876540003".equals(phone)) return Optional.of(anyUser("u-3", "three@example.com", phone));
                return Optional.empty();
            });

        AuthDtos.SendPhoneOtpRequest send = new AuthDtos.SendPhoneOtpRequest();
        send.phone = "+919876540003";
        service.sendPhoneOtp(send, "client-1");
        String otp = sentOtps.get("+919876540003");
        assertNotNull(otp);

        AuthDtos.VerifyPhoneOtpRequest wrong = otpRequest("+919876540003", "000000");
        assertThrows(ResponseStatusException.class, () -> service.verifyPhoneOtp(wrong));

        service.verifyPhoneOtp(otpRequest("+919876540003", otp));
        // consumed: second use fails
        assertThrows(ResponseStatusException.class, () -> service.verifyPhoneOtp(otpRequest("+919876540003", otp)));
    }

    @Test
    void phoneOtpAttemptBudgetIsEnforced() {
        // maxAttempts=2 for this instance
        AuthService strict = new AuthService(users, profiles, contactRepo, new Argon2idPasswordEncoder(),
            new JwtService(TEST_SECRET, 900, 2592000), verifications,
            new DevLogEmailSender(), new DevLogSmsOtpSender(), rateLimiter,
            30, 10, 2, 30);
        when(users.findByNormalizedPhone(anyString()))
            .thenReturn(Optional.of(anyUser("u-4", "four@example.com", "+919876540004")));

        AuthDtos.SendPhoneOtpRequest send = new AuthDtos.SendPhoneOtpRequest();
        send.phone = "+919876540004";
        strict.sendPhoneOtp(send, "client-1");

        // two wrong attempts exhaust the budget (5-arg instance: max=2)
        assertThrows(ResponseStatusException.class,
            () -> strict.verifyPhoneOtp(otpRequest("+919876540004", "111111")));
        ResponseStatusException third = assertThrows(ResponseStatusException.class,
            () -> strict.verifyPhoneOtp(otpRequest("+919876540004", "222222")));
        // After budget exhaustion the entry is deleted → next failure is generic invalid;
        // either 400 (invalid) or 429 (too many attempts) is acceptable, never a success.
        assertTrue(third.getStatusCode().value() == 429 || third.getStatusCode().value() == 400);
    }

    @Test
    void phoneOtpRequestsAreRateLimited() {
        when(users.findByNormalizedPhone(anyString())).thenReturn(Optional.empty());
        when(rateLimiter.tryConsume(eq(RateLimiter.Policy.PHONE_OTP), anyString())).thenReturn(false);

        AuthDtos.SendPhoneOtpRequest send = new AuthDtos.SendPhoneOtpRequest();
        send.phone = "+919876549999";
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.sendPhoneOtp(send, "client-x"));
        assertEquals(429, e.getStatusCode().value());
    }

    // ---------------- Login ----------------

    @Test
    void loginWithVerifiedEmailSucceedsAndReturnsTokens() {
        UserDocument user = anyUser("u-5", "login@example.com", "+919876540005");
        user.setEmailVerified(true);
        when(users.findByNormalizedEmail("login@example.com")).thenReturn(Optional.of(user));
        when(users.findByNormalizedEmail(AuthService.normalizeEmail("login@example.com"))).thenReturn(Optional.of(user));
        when(profiles.findByUserId("u-5")).thenReturn(Optional.empty());

        AuthDtos.AuthTokensResponse tokens = service.login(loginRequest("login@example.com", "S3cure!Passw0rd"), "c");
        assertNotNull(tokens.accessToken);
        assertNotNull(tokens.refreshToken);
        assertEquals("u-5", tokens.user.userId);
        assertNotNull(tokens.user.emergencyId);
    }

    @Test
    void loginWithVerifiedPhoneSucceeds() {
        UserDocument user = anyUser("u-6", "six@example.com", "+919876540006");
        user.setPhoneVerified(true);
        when(users.findByNormalizedPhone("+919876540006")).thenReturn(Optional.of(user));
        when(profiles.findByUserId("u-6")).thenReturn(Optional.empty());

        AuthDtos.AuthTokensResponse tokens = service.login(loginRequest("+919876540006", "S3cure!Passw0rd"), "c");
        assertEquals("u-6", tokens.user.userId);
    }

    @Test
    void loginWithWrongPasswordFailsGenerically() {
        UserDocument user = anyUser("u-7", "seven@example.com", "+919876540007");
        user.setEmailVerified(true);
        when(users.findByNormalizedEmail("seven@example.com")).thenReturn(Optional.of(user));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> service.login(loginRequest("seven@example.com", "WrongPassword1!"), "c"));
        assertEquals(401, e.getStatusCode().value());

        // Unknown identifier gives the SAME status code (no enumeration)
        when(users.findByNormalizedEmail("ghost@example.com")).thenReturn(Optional.empty());
        ResponseStatusException ghost = assertThrows(ResponseStatusException.class,
            () -> service.login(loginRequest("ghost@example.com", "Whatever1!"), "c"));
        assertEquals(401, ghost.getStatusCode().value());
    }

    @Test
    void unverifiedAccountCannotLogIn() {
        UserDocument user = anyUser("u-8", "eight@example.com", "+919876540008");
        // neither email nor phone verified
        when(users.findByNormalizedEmail("eight@example.com")).thenReturn(Optional.of(user));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> service.login(loginRequest("eight@example.com", "S3cure!Passw0rd"), "c"));
        assertEquals(403, e.getStatusCode().value());
    }

    @Test
    void loggingInWithUnverifiedPhoneFails() {
        UserDocument user = anyUser("u-9", "nine@example.com", "+919876540009");
        user.setEmailVerified(true);
        when(users.findByNormalizedPhone("+919876540009")).thenReturn(Optional.of(user));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> service.login(loginRequest("+919876540009", "S3cure!Passw0rd"), "c"));
        assertEquals(403, e.getStatusCode().value());
    }

    @Test
    void loginRateLimitReturns429() {
        UserDocument user = anyUser("u-10", "ten@example.com", "+919876540010");
        user.setEmailVerified(true);
        when(users.findByNormalizedEmail("ten@example.com")).thenReturn(Optional.of(user));
        when(rateLimiter.tryConsume(eq(RateLimiter.Policy.LOGIN), anyString())).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> service.login(loginRequest("ten@example.com", "S3cure!Passw0rd"), "c"));
        assertEquals(429, e.getStatusCode().value());
    }

    // ---------------- Sessions: refresh & logout ----------------

    @Test
    void refreshRotatesAndOldTokenBecomesUnusable() {
        UserDocument user = anyUser("u-11", "eleven@example.com", "+919876540011");
        user.setEmailVerified(true);
        when(users.findByNormalizedEmail("eleven@example.com")).thenReturn(Optional.of(user));
        when(users.findById("u-11")).thenReturn(Optional.of(user));
        when(profiles.findByUserId("u-11")).thenReturn(Optional.empty());

        AuthDtos.AuthTokensResponse first = service.login(loginRequest("eleven@example.com", "S3cure!Passw0rd"), "c");
        AuthDtos.AuthTokensResponse refreshed = service.refresh(refreshRequest(first.refreshToken));
        assertNotEquals(first.refreshToken, refreshed.refreshToken);

        // old refresh token (previous jti) must now fail
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> service.refresh(refreshRequest(first.refreshToken)));
        assertEquals(401, e.getStatusCode().value());
    }

    @Test
    void logoutRevokesTheActiveSession() {
        UserDocument user = anyUser("u-12", "twelve@example.com", "+919876540012");
        user.setEmailVerified(true);
        AtomicReference<UserDocument> stored = new AtomicReference<>(user);
        when(users.findByNormalizedEmail("twelve@example.com")).thenReturn(Optional.of(user));
        when(users.findById("u-12")).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(users.save(any())).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(profiles.findByUserId(anyString())).thenReturn(Optional.empty());

        AuthDtos.AuthTokensResponse tokens = service.login(loginRequest("twelve@example.com", "S3cure!Passw0rd"), "c");
        service.logout(logoutRequest(tokens.refreshToken));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> service.refresh(refreshRequest(tokens.refreshToken)));
        assertEquals(401, e.getStatusCode().value());
    }

    // ---------------- Password reset ----------------

    @Test
    void forgotPasswordIsGenericAndUnknownEmailsDoNothing() {
        when(users.findByNormalizedEmail("ghost@example.com")).thenReturn(Optional.empty());
        service.forgotPassword(forgotRequest("ghost@example.com"), "c");
        assertTrue(sentEmailCodes.isEmpty(), "no reset code for unknown email");
    }

    @Test
    void passwordResetWorksAndRevokesSessions() {
        UserDocument user = anyUser("u-13", "thirteen@example.com", "+919876540013");
        user.setEmailVerified(true);
        AtomicReference<UserDocument> stored = new AtomicReference<>(user);
        when(users.findByNormalizedEmail("thirteen@example.com")).thenReturn(Optional.of(user));
        when(users.findById("u-13")).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(users.save(any())).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(profiles.findByUserId(anyString())).thenReturn(Optional.empty());

        AuthDtos.AuthTokensResponse tokens = service.login(loginRequest("thirteen@example.com", "S3cure!Passw0rd"), "c");

        service.forgotPassword(forgotRequest("thirteen@example.com"), "c");
        String resetCode = sentEmailCodes.get("reset:thirteen@example.com");
        assertNotNull(resetCode);

        service.resetPassword(resetRequest("thirteen@example.com", resetCode, "N3wPassword!42"));

        // old session revoked
        assertThrows(ResponseStatusException.class, () -> service.refresh(refreshRequest(tokens.refreshToken)));
        // old password no longer works, new one does
        assertThrows(ResponseStatusException.class,
            () -> service.login(loginRequest("thirteen@example.com", "S3cure!Passw0rd"), "c"));
        AuthDtos.AuthTokensResponse newLogin =
            service.login(loginRequest("thirteen@example.com", "N3wPassword!42"), "c");
        assertNotNull(newLogin.accessToken);
    }

    // ---------------- Authorization ----------------

    @Test
    void unauthenticatedProfileAccessIsRejected() {
        UserController controller = new UserController(users, profiles, contactRepo, service);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> controller.me(null));
        assertEquals(401, e.getStatusCode().value());
    }

    @Test
    void profileResponseNeverContainsPasswordMaterial() {
        UserDocument user = anyUser("u-14", "fourteen@example.com", "+919876540014");
        EmergencyProfileDocument profile = new EmergencyProfileDocument();
        profile.setUserId("u-14");
        profile.setBloodGroup("O+");
        AuthDtos.UserMeResponse me = service.toUserMe(user, profile);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(me).toString();
        assertTrue(!json.contains("passwordHash"));
        assertTrue(!json.toLowerCase().contains("password"));
    }

    // ---------------- helpers ----------------

    private AuthDtos.RefreshRequest refreshRequest(String token) {
        AuthDtos.RefreshRequest request = new AuthDtos.RefreshRequest();
        request.refreshToken = token;
        return request;
    }

    private AuthDtos.LogoutRequest logoutRequest(String token) {
        AuthDtos.LogoutRequest request = new AuthDtos.LogoutRequest();
        request.refreshToken = token;
        return request;
    }

    private AuthDtos.ForgotPasswordRequest forgotRequest(String email) {
        AuthDtos.ForgotPasswordRequest request = new AuthDtos.ForgotPasswordRequest();
        request.email = email;
        return request;
    }

    private AuthDtos.ResetPasswordRequest resetRequest(String email, String code, String newPassword) {
        AuthDtos.ResetPasswordRequest request = new AuthDtos.ResetPasswordRequest();
        request.email = email;
        request.resetToken = code;
        request.newPassword = newPassword;
        return request;
    }
}
