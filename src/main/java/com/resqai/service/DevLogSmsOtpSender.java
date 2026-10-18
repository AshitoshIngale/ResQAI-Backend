package com.resqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEVELOPMENT-ONLY SMS adapter used when no real SMS provider is configured.
 * TEST MODE: OTPs are printed to the server log only — never returned through
 * the API. Configure resqai.sms.provider to disable it.
 */

public class DevLogSmsOtpSender implements SmsOtpSender {

    public static final Logger DEV_LOG = LoggerFactory.getLogger("RESQAI-TEST-MODE");

    @Override
    public void sendOtp(String e164Phone, String otp) {
        DEV_LOG.warn("[TEST MODE — NOT PRODUCTION] Phone OTP for {} : {}", e164Phone, otp);
    }

    @Override
    public boolean isProduction() {
        return false;
    }
}
