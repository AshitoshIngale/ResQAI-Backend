package com.resqai.exception;

import com.resqai.dto.AuthDtos;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * Uniform, generic error responses. Validation errors list field-level messages;
 * authentication failures stay non-enumerating; internal errors never leak details.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AuthDtos.ApiError> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        AuthDtos.ApiError error = new AuthDtos.ApiError("validation_error", "Please check the highlighted fields", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<AuthDtos.ApiError> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        AuthDtos.ApiError error = new AuthDtos.ApiError("validation_error", "Malformed request body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<AuthDtos.ApiError> handleStatus(ResponseStatusException e) {
        String message = e.getReason() != null ? e.getReason() : "Request failed";
        AuthDtos.ApiError error = new AuthDtos.ApiError("request_error", message);
        return ResponseEntity.status(e.getStatusCode()).body(error);
    }

    @ExceptionHandler(VerificationException.TooManyAttempts.class)
    public ResponseEntity<AuthDtos.ApiError> handleTooMany(VerificationException.TooManyAttempts e) {
        AuthDtos.ApiError error = new AuthDtos.ApiError("rate_limited", "Too many attempts. Try again later.");
        error.retryAfterSeconds = e.getRetryAfterSeconds();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<AuthDtos.ApiError> handleDuplicate(DuplicateKeyException e) {
        AuthDtos.ApiError error = new AuthDtos.ApiError("conflict", "An account with this email or number already exists");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AuthDtos.ApiError> handleIllegalArgument(IllegalArgumentException e) {
        AuthDtos.ApiError error = new AuthDtos.ApiError("validation_error", "Invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AuthDtos.ApiError> handleGeneric(Exception e) {
        // Log server-side only; the client gets a generic message with no internals.
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class)
            .error("Unhandled error: {}", e.getClass().getSimpleName());
        AuthDtos.ApiError error = new AuthDtos.ApiError("internal_error", "Something went wrong");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
