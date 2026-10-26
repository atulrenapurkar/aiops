package com.aiops.service;

import com.aiops.entity.AlertLog;
import com.aiops.repository.AlertLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datadog.polling.enabled", havingValue = "true")
public class DatadogPollerService {

    private final RestTemplate restTemplate;
    private final AlertLogRepository alertLogRepository;
    private final ActionSuggestionService actionSuggestionService;
    private final ObjectMapper objectMapper;

    @Value("${datadog.api-key}")
    private String apiKey;

    @Value("${datadog.app-key}")
    private String appKey;

    @Value("${datadog.site}")
    private String site;

    @Value("${datadog.query}")
    private String query;

    /**
     * Poll Datadog for error logs every minute (configurable via datadog.poll-interval-ms).
     */
    @Scheduled(fixedDelayString = "${datadog.poll-interval-ms}")
    public void poll() {
        log.info("Polling Datadog for errors...");
        try {
            String url = "https://api." + site + "/api/v2/logs/events/search";

            HttpHeaders headers = new HttpHeaders();
            headers.set("DD-API-KEY", apiKey);
            headers.set("DD-APPLICATION-KEY", appKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Search last 2 minutes to avoid gaps / duplicates
            Instant now  = Instant.now();
            Instant from = now.minus(2, ChronoUnit.MINUTES);

            ObjectNode requestBody = objectMapper.createObjectNode()
                    .put("query", query);
            requestBody.putObject("sort").put("field", "@timestamp").put("order", "desc");
            requestBody.putObject("page").put("limit", 100);
            requestBody.putObject("filter")
                    .put("from", from.toString())
                    .put("to", now.toString());

            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(requestBody.toString(), headers),
                    JsonNode.class);

            JsonNode body = response.getBody();
            if (body == null) return;

            for (JsonNode event : body.path("data")) {
                processEvent(event);
            }
        } catch (Exception e) {
            log.error("Datadog poll failed: {}", e.getMessage(), e);
        }
    }

    private void processEvent(JsonNode event) {
        String datadogId = event.path("id").asText();
        if (alertLogRepository.existsByDatadogId(datadogId)) return; // already processed

        JsonNode attrs   = event.path("attributes");
        String message   = attrs.path("message").asText();
        String service   = attrs.path("service").asText("unknown");
        String severity  = attrs.path("status").asText("error");
        String tagsJson  = attrs.path("tags").toString();

        AlertLog alert = AlertLog.builder()
                .datadogId(datadogId)
                .service(service)
                .message(message)
                .severity(severity)
                .tags(tagsJson)
                .build();

        alert = alertLogRepository.save(alert);

        log.info("New Datadog error from service '{}': {}", service,
                message.length() > 120 ? message.substring(0, 120) + "..." : message);

        // Async suggestion — runs in same thread for simplicity; extract to @Async if needed
        actionSuggestionService.suggestAndUpdate(alert);
    }
}
