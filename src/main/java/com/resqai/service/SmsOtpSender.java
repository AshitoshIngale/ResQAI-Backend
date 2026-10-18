package com.resqai.service;

/**
 * Abstraction over the SMS/OTP provider. Production implementations call a
 * real provider; the dev adapter logs and is clearly labelled test mode.
 */
public interface SmsOtpSender {
    void sendOtp(String e164Phone, String otp);

    boolean isProduction();
}
