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

/**
 * Calls any OpenAI-compatible {@code /chat/completions} endpoint — OpenAI itself,
 * Azure OpenAI, Groq, Together, DeepSeek, or a local server (vLLM, LM Studio,
 * text-generation-webui). Reads live config from {@link LlmSettings}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiLlmClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LlmSettings settings;

    public String chat(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage, settings.getOpenaiMaxTokens());
    }

    public String chat(String systemPrompt, String userMessage, int tokenLimit) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(settings.getOpenaiApiKey());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", settings.getOpenaiModel());
        body.put("max_tokens", tokenLimit);

        ArrayNode messages = body.putArray("messages");
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    settings.getOpenaiBaseUrl(), HttpMethod.POST,
                    new HttpEntity<>(body.toString(), headers),
                    JsonNode.class);

            JsonNode responseBody = response.getBody();
            if (responseBody == null) {
                throw new RuntimeException("Empty response from OpenAI-compatible API");
            }

            JsonNode choice = responseBody.path("choices").get(0);
            if ("length".equals(choice.path("finish_reason").asText())) {
                log.warn("Response was truncated at {} tokens — consider increasing the limit", tokenLimit);
            }

            return choice.path("message").path("content").asText();

        } catch (Exception e) {
            log.error("OpenAI-compatible API call failed: {}", e.getMessage());
            throw new RuntimeException("OpenAI-compatible API error: " + e.getMessage(), e);
        }
    }
}
