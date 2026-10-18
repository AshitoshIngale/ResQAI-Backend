package com.resqai.config;

import com.resqai.service.DevLogSmsOtpSender;
import com.resqai.service.SmsOtpSender;
import com.resqai.service.TwilioSmsOtpSender;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the SMS adapter deterministically:
 *  - resqai.sms.provider=twilio (+ credentials via env) -> TwilioSmsOtpSender
 *  - otherwise                                             -> DevLogSmsOtpSender (TEST MODE)
 */
@Configuration
public class SmsSenderConfig {

    @Bean
    public SmsOtpSender smsOtpSender(
        @Value("${resqai.sms.provider:}") String provider,
        @Value("${resqai.sms.twilio.account-sid:}") String accountSid,
        @Value("${resqai.sms.twilio.auth-token:}") String authToken,
        @Value("${resqai.sms.twilio.from-number:}") String fromNumber
    ) {
        if ("twilio".equalsIgnoreCase(provider)) {
            return new TwilioSmsOtpSender(accountSid, authToken, fromNumber);
        }
        return new DevLogSmsOtpSender();
    }
}
