package com.resqai.config;

import com.resqai.service.ProviderHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Outbound provider HTTP clients (Twilio / Sarvam / Gemini). Swappable in tests. */
@Configuration
public class ProviderConfig {

    /** Hard 10s cap for SOS-critical providers - a hung provider never hangs SOS. */
    @Bean
    @Primary
    public ProviderHttpClient providerHttpClient() {
        return new ProviderHttpClient.Simple();
    }

    /** Gemini multimodal turns upload audio and generate longer answers than
     *  plain REST calls; they need a wider window than the SOS-critical cap. */
    @Bean
    @Qualifier("geminiHttp")
    public ProviderHttpClient geminiHttpClient() {
        return new ProviderHttpClient.Simple(java.time.Duration.ofSeconds(45));
    }
}
