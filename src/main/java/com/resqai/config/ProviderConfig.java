package com.resqai.config;

import com.resqai.service.ProviderHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Outbound provider HTTP client bean (Twilio / Sarvam). Swappable in tests. */
@Configuration
public class ProviderConfig {

    @Bean
    public ProviderHttpClient providerHttpClient() {
        return new ProviderHttpClient.Simple();
    }
}
