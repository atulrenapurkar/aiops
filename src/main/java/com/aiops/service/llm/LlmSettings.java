package com.aiops.service.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mutable, in-memory LLM configuration. Seeded from application.properties at
 * startup, but every field can be changed at runtime via
 * {@code POST /api/llm/settings} (see LlmSettingsController) — no restart needed.
 */
@Component
public class LlmSettings {

    public enum Provider { anthropic, openai, ollama }

    @Getter @Setter
    private volatile Provider provider;

    @Getter @Setter private volatile String anthropicApiKey;
    @Getter @Setter private volatile String anthropicModel;
    @Getter @Setter private volatile String anthropicBaseUrl;
    @Getter @Setter private volatile int anthropicMaxTokens;

    @Getter @Setter private volatile String openaiApiKey;
    @Getter @Setter private volatile String openaiModel;
    @Getter @Setter private volatile String openaiBaseUrl;
    @Getter @Setter private volatile int openaiMaxTokens;

    @Getter @Setter private volatile String ollamaChatModel;
    @Getter @Setter private volatile String ollamaBaseUrl;

    public LlmSettings(
            @Value("${llm.provider:anthropic}") String provider,
            @Value("${anthropic.api-key:}") String anthropicApiKey,
            @Value("${anthropic.model:claude-sonnet-4-6}") String anthropicModel,
            @Value("${anthropic.base-url:https://api.anthropic.com/v1/messages}") String anthropicBaseUrl,
            @Value("${anthropic.max-tokens:2048}") int anthropicMaxTokens,
            @Value("${openai.api-key:}") String openaiApiKey,
            @Value("${openai.model:gpt-4o}") String openaiModel,
            @Value("${openai.base-url:https://api.openai.com/v1/chat/completions}") String openaiBaseUrl,
            @Value("${openai.max-tokens:2048}") int openaiMaxTokens,
            @Value("${ollama.chat-model:llama3}") String ollamaChatModel,
            @Value("${ollama.base-url:http://localhost:11434}") String ollamaBaseUrl
    ) {
        this.provider = Provider.valueOf(provider.trim().toLowerCase());
        this.anthropicApiKey = anthropicApiKey;
        this.anthropicModel = anthropicModel;
        this.anthropicBaseUrl = anthropicBaseUrl;
        this.anthropicMaxTokens = anthropicMaxTokens;
        this.openaiApiKey = openaiApiKey;
        this.openaiModel = openaiModel;
        this.openaiBaseUrl = openaiBaseUrl;
        this.openaiMaxTokens = openaiMaxTokens;
        this.ollamaChatModel = ollamaChatModel;
        this.ollamaBaseUrl = ollamaBaseUrl;
    }
}
