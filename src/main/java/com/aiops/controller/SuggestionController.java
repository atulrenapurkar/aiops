package com.aiops.controller;

import com.aiops.entity.AlertLog;
import com.aiops.repository.AlertLogRepository;
import com.aiops.service.ActionSuggestionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SuggestionController {

    private final ActionSuggestionService actionSuggestionService;
    private final AlertLogRepository alertLogRepository;

    /**
     * Manually submit an error message and get an AI-powered suggestion.
     * POST /api/suggest
     * Body: { "errorMessage": "NullPointerException in PaymentService..." }
     */
    @PostMapping("/suggest")
    public ResponseEntity<Map<String, String>> suggest(@RequestBody SuggestRequest request) {
        if (request.getErrorMessage() == null || request.getErrorMessage().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "errorMessage must not be blank"));
        }
        String suggestion = actionSuggestionService.suggest(request.getErrorMessage());
        return ResponseEntity.ok(Map.of(
                "errorMessage", request.getErrorMessage(),
                "suggestion", suggestion));
    }

    /**
     * List recent processed alerts with their suggestions.
     * GET /api/alerts?page=0&size=20
     */
    @GetMapping("/alerts")
    public ResponseEntity<Page<AlertLog>> listAlerts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<AlertLog> alerts = alertLogRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(alerts);
    }

    /**
     * Get a specific alert by ID.
     * GET /api/alerts/{id}
     */
    @GetMapping("/alerts/{id}")
    public ResponseEntity<AlertLog> getAlert(@PathVariable Long id) {
        return alertLogRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Data
    public static class SuggestRequest {
        private String errorMessage;
    }
}
