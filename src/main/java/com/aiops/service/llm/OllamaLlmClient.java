package com.aiops.service.llm;

import com.aiops.dto.OllamaChatRequest;
import com.aiops.dto.OllamaChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Calls a local Ollama server's chat endpoint, reading live config from
 * {@link LlmSettings}. No API key needed, but {@code ollamaChatModel} must
 * actually support chat (not an embedding-only model like nomic-embed-text).
 */
@Service
@RequiredArgsConstructor
public class OllamaLlmClient {

    private final RestTemplate restTemplate;
    private final LlmSettings settings;

    public String chat(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage, 0);
    }

    /** Local models aren't billed per-token, so maxTokens is accepted but not enforced. */
    public String chat(String systemPrompt, String userMessage, int maxTokens) {
        OllamaChatRequest request = OllamaChatRequest.builder()
                .model(settings.getOllamaChatModel())
                .stream(false)
                .messages(List.of(
                        new OllamaChatRequest.Message("system", systemPrompt),
                        new OllamaChatRequest.Message("user", userMessage)
                ))
                .build();

        OllamaChatResponse response = restTemplate.postForObject(
                settings.getOllamaBaseUrl() + "/api/chat", request, OllamaChatResponse.class);

        if (response == null || response.getMessage() == null) {
            throw new RuntimeException("Empty chat response from Ollama");
        }
        return response.getMessage().getContent();
    }
}
