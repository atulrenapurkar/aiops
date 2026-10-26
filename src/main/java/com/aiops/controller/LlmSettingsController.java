package com.aiops.controller;

import com.aiops.service.llm.LlmSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lets the UI (or any client) inspect and change which LLM backend answers
 * suggestions — switching provider/model/key here takes effect immediately,
 * no restart.
 */
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmSettingsController {

    private final LlmSettings settings;

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", settings.getProvider().name());

        Map<String, Object> anthropic = new LinkedHashMap<>();
        anthropic.put("model", settings.getAnthropicModel());
        anthropic.put("baseUrl", settings.getAnthropicBaseUrl());
        anthropic.put("apiKeySet", isSet(settings.getAnthropicApiKey()));
        anthropic.put("apiKeyMasked", mask(settings.getAnthropicApiKey()));
        result.put("anthropic", anthropic);

        Map<String, Object> openai = new LinkedHashMap<>();
        openai.put("model", settings.getOpenaiModel());
        openai.put("baseUrl", settings.getOpenaiBaseUrl());
        openai.put("apiKeySet", isSet(settings.getOpenaiApiKey()));
        openai.put("apiKeyMasked", mask(settings.getOpenaiApiKey()));
        result.put("openai", openai);

        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("model", settings.getOllamaChatModel());
        ollama.put("baseUrl", settings.getOllamaBaseUrl());
        result.put("ollama", ollama);

        return result;
    }

    @PostMapping("/settings")
    public ResponseEntity<?> updateSettings(@RequestBody UpdateRequest req) {
        if (req.provider() == null || req.provider().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider is required"));
        }

        LlmSettings.Provider provider;
        try {
            provider = LlmSettings.Provider.valueOf(req.provider().trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown provider: " + req.provider()));
        }

        switch (provider) {
            case anthropic -> {
                if (notBlank(req.apiKey())) settings.setAnthropicApiKey(req.apiKey().trim());
                if (notBlank(req.model())) settings.setAnthropicModel(req.model().trim());
                if (notBlank(req.baseUrl())) settings.setAnthropicBaseUrl(req.baseUrl().trim());
                if (!isSet(settings.getAnthropicApiKey())) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Anthropic API key is required"));
                }
            }
            case openai -> {
                if (notBlank(req.apiKey())) settings.setOpenaiApiKey(req.apiKey().trim());
                if (notBlank(req.model())) settings.setOpenaiModel(req.model().trim());
                if (notBlank(req.baseUrl())) settings.setOpenaiBaseUrl(req.baseUrl().trim());
                if (!isSet(settings.getOpenaiApiKey())) {
                    return ResponseEntity.badRequest().body(Map.of("error", "OpenAI API key is required"));
                }
            }
            case ollama -> {
                if (notBlank(req.model())) settings.setOllamaChatModel(req.model().trim());
                if (notBlank(req.baseUrl())) settings.setOllamaBaseUrl(req.baseUrl().trim());
            }
        }

        settings.setProvider(provider);
        return ResponseEntity.ok(getSettings());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    private static String mask(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "••••";
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    public record UpdateRequest(String provider, String apiKey, String model, String baseUrl) {}
}
