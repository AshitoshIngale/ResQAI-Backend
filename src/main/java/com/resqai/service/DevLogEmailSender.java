package com.resqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEVELOPMENT-ONLY email adapter used when no SMTP provider is configured.
 * TEST MODE: codes are printed to the server log (never returned through the
 * API). Any response that depends on it is explicitly labelled test mode by
 * the service layer. This class must never be active in production — configure
 * spring.mail.host to disable it.
 */

public class DevLogEmailSender implements EmailSender {

    public static final Logger DEV_LOG = LoggerFactory.getLogger("RESQAI-TEST-MODE");

    @Override
    public void sendVerificationCode(String to, String code) {
        DEV_LOG.warn("[TEST MODE — NOT PRODUCTION] Email verification code for {} : {}", to, code);
    }

    @Override
    public void sendPasswordResetCode(String to, String code) {
        DEV_LOG.warn("[TEST MODE — NOT PRODUCTION] Password reset code for {} : {}", to, code);
    }

    @Override
    public boolean isProduction() {
        return false;
    }
}
