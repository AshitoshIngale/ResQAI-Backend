package com.resqai.exception;

import com.resqai.service.VerificationService;

/** Thrown by VerificationService when a code cannot be consumed. */
public class VerificationException extends RuntimeException {
    public VerificationException(String message) { super(message); }

    public static class Expired extends VerificationException {
        public Expired() { super("expired"); }
    }

    public static class Invalid extends VerificationException {
        public Invalid() { super("invalid"); }
    }

    public static class TooManyAttempts extends VerificationException {
        private final long retryAfterSeconds;

        public TooManyAttempts(long retryAfterSeconds) {
            super("too-many-attempts");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long getRetryAfterSeconds() { return retryAfterSeconds; }
    }
}
