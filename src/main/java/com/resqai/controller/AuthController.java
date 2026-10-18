package com.resqai.controller;

import com.resqai.dto.AuthDtos;
import com.resqai.service.AuthService;

import com.resqai.dto.AuthDtos.ForgotPasswordRequest;
import com.resqai.dto.AuthDtos.LoginRequest;
import com.resqai.dto.AuthDtos.LogoutRequest;
import com.resqai.dto.AuthDtos.RefreshRequest;
import com.resqai.dto.AuthDtos.RegisterRequest;
import com.resqai.dto.AuthDtos.ResetPasswordRequest;
import com.resqai.dto.AuthDtos.SendPhoneOtpRequest;
import com.resqai.dto.AuthDtos.VerifyEmailRequest;
import com.resqai.dto.AuthDtos.VerifyPhoneOtpRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Versioned auth endpoints. Registration is NOT complete (login-enabled) until
 * email and phone verifications succeed.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthDtos.RegisterResponse> register(@Valid @RequestBody RegisterRequest request,
                                                              HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(authService.register(request));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<AuthDtos.MessageResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request);
        return ResponseEntity.ok(new AuthDtos.MessageResponse("Email verified"));
    }

    @PostMapping("/send-phone-otp")
    public ResponseEntity<AuthDtos.MessageResponse> sendPhoneOtp(@Valid @RequestBody SendPhoneOtpRequest request,
                                                                 HttpServletRequest http) {
        return ResponseEntity.ok(authService.sendPhoneOtp(request, clientKey(http)));
    }

    @PostMapping("/verify-phone-otp")
    public ResponseEntity<AuthDtos.MessageResponse> verifyPhoneOtp(@Valid @RequestBody VerifyPhoneOtpRequest request) {
        authService.verifyPhoneOtp(request);
        return ResponseEntity.ok(new AuthDtos.MessageResponse("Phone verified"));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.AuthTokensResponse> login(@Valid @RequestBody LoginRequest request,
                                                             HttpServletRequest http) {
        return ResponseEntity.ok(authService.login(request, clientKey(http)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDtos.AuthTokensResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<AuthDtos.MessageResponse> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(new AuthDtos.MessageResponse("Signed out"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<AuthDtos.MessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                                                   HttpServletRequest http) {
        authService.forgotPassword(request, clientKey(http));
        return ResponseEntity.ok(new AuthDtos.MessageResponse(
            "If that email is registered, a reset code has been sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<AuthDtos.MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(new AuthDtos.MessageResponse("Password updated. Please sign in again."));
    }

    private String clientKey(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        String ip = forwarded != null ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }
}
