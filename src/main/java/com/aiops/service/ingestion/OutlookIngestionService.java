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
public class OutlookIngestionService {

    private final RestTemplate restTemplate;
    private final MicrosoftAuthService authService;
    private final VectorStoreService vectorStoreService;

    @Value("${microsoft.graph-base-url:https://graph.microsoft.com/v1.0}")
    private String graphBase;

    @Value("${microsoft.outlook.user-id}")
    private String userId;

    public int ingest() {
        return ingest(null, null);
    }

    /** userIdOverride can be a Graph user GUID or, more commonly, the mailbox's email address. */
    public int ingest(String userIdOverride, Long applicationId) {
        String uid = (userIdOverride != null && !userIdOverride.isBlank()) ? userIdOverride : userId;
        log.info("Starting Outlook ingestion for user: {}", uid);
        int count = 0;
        // Fetch recent emails, body in text format
        String url = graphBase + "/users/" + uid
                + "/messages?$top=50&$select=id,subject,from,receivedDateTime,body"
                + "&$filter=isDraft eq false";

        while (url != null) {
            HttpHeaders headers = authService.graphHeaders();
            // Request plain-text body
            headers.set("Prefer", "outlook.body-content-type=\"text\"");

            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class);

            JsonNode body = response.getBody();
            if (body == null) break;

            for (JsonNode email : body.path("value")) {
                try {
                    String emailId  = email.path("id").asText();
                    String subject  = email.path("subject").asText("(no subject)");
                    String from     = email.path("from").path("emailAddress").path("address").asText("unknown");
                    String received = email.path("receivedDateTime").asText();
                    String content  = email.path("body").path("content").asText();

                    if (content.isBlank()) continue;

                    String title    = subject + " — from " + from;
                    String metadata = String.format(
                            "{\"from\":\"%s\",\"subject\":\"%s\",\"receivedAt\":\"%s\"}",
                            from, subject.replace("\"", "'"), received);

                    vectorStoreService.store(SourceType.OUTLOOK, emailId, title, content, metadata, applicationId);
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to process email: {}", e.getMessage());
                }
            }

            JsonNode nextLink = body.path("@odata.nextLink");
            url = nextLink.isMissingNode() ? null : nextLink.asText();
        }

        log.info("Outlook ingestion complete. Stored {} emails.", count);
        return count;
    }
}
