package com.aiops.service.ingestion;

import com.aiops.entity.SourceType;
import com.aiops.service.VectorStoreService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class TeamsIngestionService {

    private final RestTemplate restTemplate;
    private final MicrosoftAuthService authService;
    private final VectorStoreService vectorStoreService;

    @Value("${microsoft.graph-base-url:https://graph.microsoft.com/v1.0}")
    private String graphBase;

    @Value("${microsoft.teams.team-id}")
    private String teamId;

    @Value("${microsoft.teams.channel-id}")
    private String channelId;

    public int ingest() {
        return ingest(null, null);
    }

    /**
     * If bearerTokenOverride is set, it's used directly instead of the global OAuth app.
     * NOTE: this only overrides auth — it always pulls the globally configured
     * team-id/channel-id. A project's own teamsChannelUrls are stored but not yet
     * parsed into a team/channel pair (Teams deep links encode a URL-escaped thread
     * ID and a groupId that needs an extra Graph lookup to resolve to a team ID),
     * so per-project channel targeting isn't wired up yet.
     */
    public int ingest(String bearerTokenOverride, Long applicationId) {
        log.info("Starting Teams ingestion for channel: {}", channelId);
        int count = 0;
        String url = graphBase + "/teams/" + teamId + "/channels/" + channelId
                + "/messages?$top=50";

        HttpHeaders headers;
        if (bearerTokenOverride != null && !bearerTokenOverride.isBlank()) {
            headers = new HttpHeaders();
            headers.setBearerAuth(bearerTokenOverride);
            headers.setContentType(MediaType.APPLICATION_JSON);
        } else {
            headers = authService.graphHeaders();
        }

        while (url != null) {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class);

            JsonNode body = response.getBody();
            if (body == null) break;

            for (JsonNode msg : body.path("value")) {
                try {
                    String msgId   = msg.path("id").asText();
                    String content = msg.path("body").path("content").asText();
                    String from    = msg.path("from").path("user").path("displayName").asText("Unknown");
                    String created = msg.path("createdDateTime").asText();

                    if (content.isBlank() || content.equals("<systemEventMessage/>")) continue;

                    // strip HTML from rich-text messages
                    content = content.replaceAll("<[^>]+>", " ").replaceAll("\\s{2,}", " ").trim();

                    String title    = "Teams message from " + from + " at " + created;
                    String metadata = String.format(
                            "{\"teamId\":\"%s\",\"channelId\":\"%s\",\"from\":\"%s\",\"createdAt\":\"%s\"}",
                            teamId, channelId, from, created);

                    vectorStoreService.store(SourceType.TEAMS, msgId, title, content, metadata, applicationId);
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to process Teams message: {}", e.getMessage());
                }
            }

            JsonNode nextLink = body.path("@odata.nextLink");
            url = nextLink.isMissingNode() ? null : nextLink.asText();
        }

        log.info("Teams ingestion complete. Stored {} messages.", count);
        return count;
    }
}
