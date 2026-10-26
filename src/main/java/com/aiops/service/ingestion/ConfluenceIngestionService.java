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

import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfluenceIngestionService {

    private static final Pattern PAGE_ID_PATTERN = Pattern.compile("/pages/(\\d+)");

    private final RestTemplate restTemplate;
    private final VectorStoreService vectorStoreService;

    @Value("${confluence.base-url}")
    private String baseUrl;

    @Value("${confluence.username}")
    private String username;

    @Value("${confluence.api-token}")
    private String apiToken;

    @Value("${confluence.space-key}")
    private String spaceKey;

    /** Global demo/default ingestion — pulls an entire space using application.properties creds. */
    public int ingest() {
        log.info("Starting Confluence ingestion for space: {}", spaceKey);
        HttpHeaders headers = buildHeaders(username, apiToken);
        int count = 0;
        String nextUrl = baseUrl + "/rest/api/content?type=page&spaceKey=" + spaceKey
                + "&expand=body.storage,version&limit=50";

        while (nextUrl != null) {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    nextUrl, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

            JsonNode body = response.getBody();
            if (body == null) break;

            JsonNode results = body.path("results");
            for (JsonNode page : results) {
                try {
                    String pageId    = page.path("id").asText();
                    String title     = page.path("title").asText();
                    String htmlBody  = page.path("body").path("storage").path("value").asText();
                    String plainText = stripHtml(htmlBody);

                    if (plainText.isBlank()) continue;

                    String metadata = String.format(
                            "{\"spaceKey\":\"%s\",\"url\":\"%s/pages/%s\"}",
                            spaceKey, baseUrl, pageId);

                    vectorStoreService.store(SourceType.CONFLUENCE, pageId, title, plainText, metadata);
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to process Confluence page: {}", e.getMessage());
                }
            }

            // follow pagination
            JsonNode nextLink = body.path("_links").path("next");
            nextUrl = nextLink.isMissingNode() ? null : baseUrl + nextLink.asText();
        }

        log.info("Confluence ingestion complete. Stored {} pages.", count);
        return count;
    }

    /**
     * Per-project ingestion — fetches specific page URLs (extracting the page ID from
     * each) rather than a whole space. Falls back to the global base URL / credentials
     * for any override left blank.
     */
    public int ingestPages(List<String> pageUrls, String baseUrlOverride, String usernameOverride, String apiKeyOverride, Long applicationId) {
        if (pageUrls == null || pageUrls.isEmpty()) return 0;

        String base = notBlank(baseUrlOverride) ? baseUrlOverride : this.baseUrl;
        HttpHeaders headers = buildHeaders(
                notBlank(usernameOverride) ? usernameOverride : this.username,
                notBlank(apiKeyOverride) ? apiKeyOverride : this.apiToken);

        int count = 0;
        for (String pageUrl : pageUrls) {
            String pageId = extractPageId(pageUrl);
            if (pageId == null) {
                log.warn("Could not extract a Confluence page ID from URL: {}", pageUrl);
                continue;
            }
            try {
                String url = base + "/rest/api/content/" + pageId + "?expand=body.storage,version";
                ResponseEntity<JsonNode> response = restTemplate.exchange(
                        url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

                JsonNode page = response.getBody();
                if (page == null) continue;

                String title     = page.path("title").asText();
                String htmlBody  = page.path("body").path("storage").path("value").asText();
                String plainText = stripHtml(htmlBody);
                if (plainText.isBlank()) continue;

                String metadata = String.format("{\"url\":\"%s\"}", pageUrl.replace("\"", "'"));
                vectorStoreService.store(SourceType.CONFLUENCE, pageId, title, plainText, metadata, applicationId);
                count++;
            } catch (Exception e) {
                log.warn("Failed to fetch Confluence page {}: {}", pageUrl, e.getMessage());
            }
        }
        return count;
    }

    private String extractPageId(String pageUrl) {
        Matcher m = PAGE_ID_PATTERN.matcher(pageUrl);
        if (m.find()) return m.group(1);
        if (pageUrl.matches("\\d+")) return pageUrl;
        return null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private HttpHeaders buildHeaders(String user, String token) {
        String credentials = Base64.getEncoder()
                .encodeToString((user + ":" + token).getBytes());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + credentials);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Minimal HTML stripper — good enough for plain text extraction */
    private String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ")
                   .replaceAll("&nbsp;", " ")
                   .replaceAll("&amp;", "&")
                   .replaceAll("&lt;", "<")
                   .replaceAll("&gt;", ">")
                   .replaceAll("\\s{2,}", " ")
                   .trim();
    }
}
