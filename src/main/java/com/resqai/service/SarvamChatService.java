package com.resqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sarvam Chat V2 client (OpenAI-compatible) powering the emergency
 * conversation engine. The model is voice-agent-tuned
 * (sarvam-105b-conversations by default) and speaks en/hi/mr natively.
 *
 * Endpoint: POST https://api.sarvam.ai/v2/chat/completions
 * Auth header: api-subscription-key (the SAME Sarvam key already used for
 * STT/TTS in this project - no new credential type).
 *
 * Safety design: the conversation engine NEVER lets this model invent
 * medical instructions. The system prompt is built from the versioned
 * first-aid knowledge base, and every reply is post-validated by
 * EmergencyConversationService before being spoken. If the chat API is
 * unavailable or unconfigured, callers fall back to the deterministic
 * first-aid scripts (existing FirstAidOrchestrator behavior).
 */
@Service
public class SarvamChatService {

    private static final String CHAT_URL = "https://api.sarvam.ai/v2/chat/completions";

    private final ProviderHttpClient http;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public SarvamChatService(ProviderHttpClient http,
                             @Value("${resqai.sarvam.api-key:}") String apiKey,
                             @Value("${resqai.sarvam.chat-model:sarvam-105b-conversations}") String model) {
        this.http = http;
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public record ChatTurn(String role, String content) {}

    public record ChatResult(String content, String model) {}

    /**
     * Runs one completion over the supplied conversation.
     *
     * @param sessionAffinity stable per-conversation id for prompt caching
     * @param maxTokens output budget (voice replies stay small)
     */
    public ChatResult complete(java.util.List<ChatTurn> messages,
                               double temperature,
                               int maxTokens,
                               String sessionAffinity,
                               String responseJsonSchema) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        ArrayNode messagesNode = body.putArray("messages");
        for (ChatTurn turn : messages) {
            ObjectNode msg = messagesNode.addObject();
            msg.put("role", turn.role());
            msg.put("content", turn.content());
        }
        if (responseJsonSchema != null && !responseJsonSchema.isBlank()) {
            ObjectNode rf = body.putObject("response_format");
            rf.put("type", "json_schema");
            ObjectNode js = rf.putObject("json_schema");
            js.put("name", "resqai_extraction");
            js.set("schema", mapper.readTree(responseJsonSchema));
            js.put("strict", true);
        }

        byte[] payload = mapper.writeValueAsBytes(body);
        String authHeader = "api-subscription-key " + apiKey;
        ProviderHttpClient.ProviderResponse response = http.send(
            "POST", CHAT_URL, "application/json", authHeader, payload);
        if (response.statusCode != 200) {
            throw new IllegalStateException("Sarvam chat failed with HTTP "
                + response.statusCode);
        }
        JsonNode root = mapper.readTree(response.body);
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            throw new IllegalStateException("Sarvam chat returned no content");
        }
        return new ChatResult(contentNode.asText(), root.path("model").asText(model));
    }
}
