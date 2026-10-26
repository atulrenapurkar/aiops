package com.aiops.service;

import com.aiops.entity.AlertLog;
import com.aiops.service.ingestion.MicrosoftAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class TeamsNotificationService {

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";

    private final RestTemplate restTemplate;
    private final MicrosoftAuthService authService;
    private final ObjectMapper objectMapper;

    @Value("${microsoft.teams.team-id}")
    private String teamId;

    @Value("${microsoft.teams.channel-id}")
    private String channelId;

    /**
     * Post an AIOps suggestion as a rich card to the configured Teams channel.
     */
    public void postSuggestion(AlertLog alert, String suggestion) {
        try {
            String url = GRAPH_BASE + "/teams/" + teamId + "/channels/" + channelId + "/messages";

            // Build an Adaptive-Card-style HTML body (Graph API accepts HTML for channel messages)
            String htmlBody = String.format("""
                    <h3>🚨 AIOps Alert — %s</h3>
                    <p><b>Service:</b> %s &nbsp;|&nbsp; <b>Severity:</b> %s</p>
                    <p><b>Error:</b><br/>%s</p>
                    <hr/>
                    <h4>💡 Suggested Action</h4>
                    <p>%s</p>
                    """,
                    alert.getDatadogId(),
                    alert.getService(),
                    alert.getSeverity(),
                    escapeHtml(alert.getMessage()),
                    suggestion.replace("\n", "<br/>"));

            ObjectNode body = objectMapper.createObjectNode();
            body.putObject("body")
                    .put("contentType", "html")
                    .put("content", htmlBody);

            restTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(body.toString(), authService.graphHeaders()),
                    String.class);

            log.info("Posted suggestion to Teams channel for alert {}", alert.getDatadogId());
        } catch (Exception e) {
            log.error("Failed to post Teams notification: {}", e.getMessage());
        }
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }
}
