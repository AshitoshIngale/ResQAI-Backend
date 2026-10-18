package com.resqai.service;

/**
 * Abstraction over the email provider. A production implementation sends real
 * mail through the configured provider; the dev adapter is clearly marked.
 */
public interface EmailSender {
    void sendVerificationCode(String to, String code);

    void sendPasswordResetCode(String to, String code);

    boolean isProduction();
}
