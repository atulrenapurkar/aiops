package com.aiops.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/** Calls the Anthropic Messages API, reading live config from {@link LlmSettings}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnthropicLlmClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LlmSettings settings;

    public String chat(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage, settings.getAnthropicMaxTokens());
    }

    public String chat(String systemPrompt, String userMessage, int tokenLimit) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", settings.getAnthropicApiKey());
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", settings.getAnthropicModel());
        body.put("max_tokens", tokenLimit);
        body.put("system", systemPrompt);

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    settings.getAnthropicBaseUrl(), HttpMethod.POST,
                    new HttpEntity<>(body.toString(), headers),
                    JsonNode.class);

            JsonNode responseBody = response.getBody();
            if (responseBody == null) {
                throw new RuntimeException("Empty response from Anthropic API");
            }

            JsonNode stopReason = responseBody.path("stop_reason");
            if ("max_tokens".equals(stopReason.asText())) {
                log.warn("Claude response was truncated at {} tokens — consider increasing the limit", tokenLimit);
            }

            return responseBody.path("content").get(0).path("text").asText();

        } catch (Exception e) {
            log.error("Anthropic API call failed: {}", e.getMessage());
            throw new RuntimeException("Anthropic API error: " + e.getMessage(), e);
        }
    }
}
