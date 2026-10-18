package com.resqai.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * PRODUCTION email adapter backed by spring-boot-starter-mail (SMTP).
 * Enabled only when spring.mail.host is configured to a real provider.
 * Sends the verification code as the message body; codes are never logged here.
 */


public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, @Value("${resqai.mail.from:no-reply@resqai.example}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendVerificationCode(String to, String code) {
        send(to, "ResQAI email verification",
            "<p>Your ResQAI verification code is:</p><h2>" + code + "</h2>" +
                "<p>This code expires soon. If you did not request it, ignore this email.</p>");
    }

    @Override
    public void sendPasswordResetCode(String to, String code) {
        send(to, "ResQAI password reset",
            "<p>Your ResQAI password reset code is:</p><h2>" + code + "</h2>" +
                "<p>This code expires soon. If you did not request it, ignore this email.</p>");
    }

    @Override
    public boolean isProduction() {
        return true;
    }

    private void send(String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Email sent to {} (subject={})", to, subject);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getClass().getSimpleName());
            throw new IllegalStateException("Email delivery failed", e);
        }
    }
}
