package com.aiops.service.llm;

/**
 * Abstraction over a chat-completion backend. The active implementation is
 * selected at startup via the {@code llm.provider} property — swapping
 * providers (or their API key/endpoint) never requires a code change.
 */
public interface LlmClient {

    String chat(String systemPrompt, String userMessage);

    String chat(String systemPrompt, String userMessage, int maxTokens);
}
