package com.resqai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * PRODUCTION SMS adapter template for Twilio's Programmable Messaging API.
 * Activated only when resqai.sms.provider=twilio AND credentials are present
 * via environment variables (never hardcoded, never committed).
 * Replace/extend with your actual provider (MSG91, AWS SNS...) as needed.
 */


public class TwilioSmsOtpSender implements SmsOtpSender {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsOtpSender.class);

    private final String accountSid;
    private final String authToken;
    private final String fromNumber;
    private final RestClient restClient;

    public TwilioSmsOtpSender(
        @Value("${resqai.sms.twilio.account-sid}") String accountSid,
        @Value("${resqai.sms.twilio.auth-token}") String authToken,
        @Value("${resqai.sms.twilio.from-number}") String fromNumber
    ) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromNumber = fromNumber;
        this.restClient = RestClient.builder()
            .baseUrl("https://api.twilio.com/2010-04-01")
            .build();
    }

    @Override
    public void sendOtp(String e164Phone, String otp) {
        String url = "/Accounts/" + accountSid + "/Messages.json";
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", e164Phone);
        form.add("From", fromNumber);
        form.add("Body", "Your ResQAI verification code is: " + otp +
            ". It expires soon. Never share this code.");

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(accountSid, authToken);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            restClient.post()
                .uri(url)
                .headers(h -> h.addAll(headers))
                .body(form)
                .retrieve()
                .toBodilessEntity();
            log.info("SMS OTP dispatched to {}", e164Phone);
        } catch (Exception e) {
            log.error("SMS delivery failed: {}", e.getClass().getSimpleName());
            throw new IllegalStateException("SMS delivery failed", e);
        }
    }

    @Override
    public boolean isProduction() {
        return true;
    }
}
