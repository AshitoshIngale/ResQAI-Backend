package com.resqai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Request/response DTOs for /api/v1/auth and /api/v1/users.
 * Raw MongoDB documents are never serialized to clients.
 */
public final class AuthDtos {
    private AuthDtos() {}

    // ---------- Requests ----------

    public static class RegisterRequest {
        @NotBlank @Size(min = 3, max = 120)
        public String fullName;

        @NotBlank @jakarta.validation.constraints.Email @Size(max = 200)
        public String email;

        /** E.164. */
        @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "phone must be E.164, e.g. +919876543210")
        public String phone;

        @NotBlank @Size(min = 10, max = 128)
        public String password;

        @Size(max = 10) @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$", message = "dateOfBirth must be YYYY-MM-DD")
        public String dateOfBirth;

        @Pattern(regexp = "^(A|B|AB|O)[+-]$|^UNKNOWN$|^$", message = "unsupported blood group")
        public String bloodGroup;

        @Size(max = 120)
        public String emergencyContactName;

        @Pattern(regexp = "^$|^\\+[1-9][0-9]{7,14}$", message = "emergencyContactPhone must be E.164")
        public String emergencyContactPhone;

        @Size(max = 10)
        public String preferredLanguage;

        public Boolean abhaIntended = false;

        @NotNull
        public Long acceptedTermsAt;
    }

    public static class VerifyEmailRequest {
        @NotBlank @jakarta.validation.constraints.Email
        public String email;

        @NotBlank @Size(min = 6, max = 64)
        public String token;
    }

    public static class SendPhoneOtpRequest {
        @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$")
        public String phone;
    }

    public static class VerifyPhoneOtpRequest {
        @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$")
        public String phone;

        @NotBlank @Pattern(regexp = "^[0-9]{6}$")
        public String otp;
    }

    public static class LoginRequest {
        /** Verified email or verified E.164 phone. */
        @NotBlank
        public String identifier;

        @NotBlank
        public String password;
    }

    public static class RefreshRequest {
        @NotBlank
        public String refreshToken;
    }

    public static class LogoutRequest {
        @NotBlank
        public String refreshToken;
    }

    public static class ForgotPasswordRequest {
        @NotBlank @jakarta.validation.constraints.Email
        public String email;
    }

    public static class ResetPasswordRequest {
        @NotBlank @jakarta.validation.constraints.Email
        public String email;

        @NotBlank
        public String resetToken;

        @NotBlank @Size(min = 10, max = 128)
        public String newPassword;
    }

    public static class EmergencyContactDto {
        @NotBlank @Size(max = 120)
        public String name;

        @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$")
        public String phone;

        @Size(max = 60)
        public String relationship;
    }

    public static class UpdateEmergencyProfileRequest {
        @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$")
        public String dateOfBirth;

        @Pattern(regexp = "^(A|B|AB|O)[+-]$|^UNKNOWN$|^$")
        public String bloodGroup;

        public List<String> allergies;
        public List<String> medicalConditions;
        public List<String> currentMedications;
        public List<EmergencyContactDto> emergencyContacts;
    }

    // ---------- Responses ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApiError {
        public String error;
        public String message;
        public Map<String, String> fieldErrors;
        public Long retryAfterSeconds;

        public ApiError(String error, String message) {
            this.error = error;
            this.message = message;
        }

        public ApiError(String error, String message, Map<String, String> fieldErrors) {
            this.error = error;
            this.message = message;
            this.fieldErrors = fieldErrors;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RegisterResponse {
        public String userId;
        public boolean emailVerificationRequired;
        public boolean phoneVerificationRequired;
        /** States the delivery mode honestly (test mode when no provider configured). */
        public String message;

        public RegisterResponse(String userId, boolean emailRequired, boolean phoneRequired, String message) {
            this.userId = userId;
            this.emailVerificationRequired = emailRequired;
            this.phoneVerificationRequired = phoneRequired;
            this.message = message;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MessageResponse {
        public String message;
        public Long retryAfterSeconds;

        public MessageResponse(String message) { this.message = message; }
        public MessageResponse(String message, Long retryAfterSeconds) {
            this.message = message;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UserMeResponse {
        public String userId;
        public String fullName;
        public String email;
        public boolean emailVerified;
        public String phone;
        public boolean phoneVerified;
        public String emergencyId;
        /** Opaque QR/lookup code — safe to store client-side for offline QR display. */
        public String emergencyCode;
        public String abhaStatus;
        public String preferredLanguage;
        public long createdAt;
        public EmergencyProfileDto emergencyProfile;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EmergencyProfileDto {
        public String dateOfBirth;
        public String bloodGroup;
        public List<String> allergies;
        public List<String> medicalConditions;
        public List<String> currentMedications;
        public String infoSource;
        public List<EmergencyContactDto> emergencyContacts;
    }

    public static class EmergencyContactDtoOut {
        public String name;
        public String phone;
        public String relationship;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuthTokensResponse {
        public String accessToken;
        public String refreshToken;
        public long expiresInSeconds;
        public UserMeResponse user;

        public AuthTokensResponse(String accessToken, String refreshToken, long expiresInSeconds, UserMeResponse user) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresInSeconds = expiresInSeconds;
            this.user = user;
        }
    }
}
