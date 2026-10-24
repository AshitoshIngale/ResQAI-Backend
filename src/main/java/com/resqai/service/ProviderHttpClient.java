package com.resqai.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal HTTP abstraction for outbound provider calls (Twilio / Sarvam) so
 * unit tests can substitute deterministic fake transports. Never used for the
 * offline conversation path — providers are online-only integrations.
 */
public interface ProviderHttpClient {

    class ProviderResponse {
        public final int statusCode;
        public final String body;

        public ProviderResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
        }
    }

    /** @throws java.io.IOException on network/timeout failures. */
    ProviderResponse send(String method, String url, String contentType,
                          String authHeader, byte[] body) throws Exception;

    /**
     * Same as send() but with an explicit User-Agent (required by APIs such
     * as OpenStreetMap Nominatim). Default delegates; only the real transport
     * needs the header, test fakes are unaffected.
     */
    default ProviderResponse sendWithUserAgent(String method, String url, String contentType,
                                               String authHeader, String userAgent,
                                               byte[] body) throws Exception {
        return send(method, url, contentType, authHeader, body);
    }

    /** Real implementation with hard timeouts — a hung provider never hangs SOS. */
    class Simple implements ProviderHttpClient {

        private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        private final Duration requestTimeout;

        public Simple() {
            this(Duration.ofSeconds(10));
        }

        public Simple(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        private String pendingUserAgent;

        @Override
        public ProviderResponse sendWithUserAgent(String method, String url, String contentType,
                                                  String authHeader, String userAgent,
                                                  byte[] body) throws Exception {
            this.pendingUserAgent = userAgent;
            try {
                return send(method, url, contentType, authHeader, body);
            } finally {
                this.pendingUserAgent = null;
            }
        }

        @Override
        public ProviderResponse send(String method, String url, String contentType,
                                     String authHeader, byte[] body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", contentType);
            if (authHeader != null && !authHeader.isBlank()) {
                builder.header("Authorization", authHeader);
            }
            builder.header("User-Agent",
                pendingUserAgent == null ? "ResQAI-Backend/1.0" : pendingUserAgent);
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(
                body == null ? new byte[0] : body));
            HttpResponse<byte[]> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new ProviderResponse(
                response.statusCode(), new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
