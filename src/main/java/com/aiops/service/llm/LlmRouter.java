package com.aiops.service.llm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The only {@link LlmClient} bean in the app. Dispatches each call to whichever
 * provider {@link LlmSettings} currently points at — switching providers (via
 * the UI or {@code POST /api/llm/settings}) takes effect on the very next call.
 */
@Service
@RequiredArgsConstructor
public class LlmRouter implements LlmClient {

    private final AnthropicLlmClient anthropicLlmClient;
    private final OpenAiLlmClient openAiLlmClient;
    private final OllamaLlmClient ollamaLlmClient;
    private final LlmSettings settings;

    @Override
    public String chat(String systemPrompt, String userMessage) {
        return switch (settings.getProvider()) {
            case anthropic -> anthropicLlmClient.chat(systemPrompt, userMessage);
            case openai -> openAiLlmClient.chat(systemPrompt, userMessage);
            case ollama -> ollamaLlmClient.chat(systemPrompt, userMessage);
        };
    }

    @Override
    public String chat(String systemPrompt, String userMessage, int maxTokens) {
        return switch (settings.getProvider()) {
            case anthropic -> anthropicLlmClient.chat(systemPrompt, userMessage, maxTokens);
            case openai -> openAiLlmClient.chat(systemPrompt, userMessage, maxTokens);
            case ollama -> ollamaLlmClient.chat(systemPrompt, userMessage, maxTokens);
        };
    }
}
