package com.resqai.config;

import com.resqai.service.DevLogEmailSender;
import com.resqai.service.EmailSender;
import com.resqai.service.SmtpEmailSender;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Chooses the email adapter deterministically:
 *  - real SMTP host configured           -> production SmtpEmailSender
 *  - unset OR placeholder host (dev/test) -> DevLogEmailSender (clearly TEST MODE)
 *
 * Placeholder hosts (smtp.example.com, REPLACE_*) count as "not configured" so
 * a fresh checkout never accidentally emails a fake domain.
 */
@Configuration
public class EmailSenderConfig {

    private static final String[] PLACEHOLDER_HOSTS = {"smtp.example.com", "localhost"};

    @Bean
    public EmailSender emailSender(
        @Value("${spring.mail.host:}") String mailHost,
        ObjectProvider<JavaMailSender> mailSenderProvider,
        @Value("${resqai.mail.from:no-reply@resqai.example}") String from
    ) {
        if (isConfigured(mailHost)) {
            return new SmtpEmailSender(mailSenderProvider.getObject(), from);
        }
        return new DevLogEmailSender();
    }

    static boolean isConfigured(String mailHost) {
        if (mailHost == null || mailHost.isBlank()) return false;
        String host = mailHost.trim().toLowerCase();
        if (host.startsWith("replace_")) return false;
        for (String placeholder : PLACEHOLDER_HOSTS) {
            if (host.equals(placeholder)) return false;
        }
        return true;
    }
}
