package com.aiops.service.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

/**
 * Acquires and caches a Microsoft Graph API access token
 * via the OAuth2 client_credentials flow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MicrosoftAuthService {

    private final RestTemplate restTemplate;

    @Value("${microsoft.auth-base-url:https://login.microsoftonline.com}")
    private String authBaseUrl;

    @Value("${microsoft.tenant-id}")
    private String tenantId;

    @Value("${microsoft.client-id}")
    private String clientId;

    @Value("${microsoft.client-secret}")
    private String clientSecret;

    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return cachedToken;
        }

        String tokenUrl = authBaseUrl + "/" + tenantId + "/oauth2/v2.0/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("scope", "https://graph.microsoft.com/.default");

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                tokenUrl, HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);

        JsonNode json = response.getBody();
        if (json == null || !json.has("access_token")) {
            throw new RuntimeException("Failed to obtain Microsoft Graph access token");
        }

        cachedToken = json.get("access_token").asText();
        int expiresIn = json.path("expires_in").asInt(3600);
        tokenExpiry = Instant.now().plusSeconds(expiresIn);

        log.info("Obtained new Microsoft Graph token, expires in {}s", expiresIn);
        return cachedToken;
    }

    public HttpHeaders graphHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
