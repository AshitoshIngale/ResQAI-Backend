package com.resqai.config;

import com.resqai.ws.EmergencyVoiceWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the real-time voice WebSocket (spec PART 7): the browser/native
 * client connects with its short-lived session token in the query string;
 * the handler validates it before accepting audio. The path is open at the
 * HTTP security layer because WebSocket authentication happens in the
 * handler itself (session-token lookup + expiry check).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EmergencyVoiceWebSocketHandler voiceHandler;

    public WebSocketConfig(EmergencyVoiceWebSocketHandler voiceHandler) {
        this.voiceHandler = voiceHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceHandler, "/ws/emergency/voice/{incidentId}")
            .setAllowedOrigins("*"); // native app + WebView origins; auth in-handler
    }
}
