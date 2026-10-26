package com.aiops.service;

import com.aiops.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaService {

    private final RestTemplate restTemplate;

    @Value("${ollama.base-url}")
    private String baseUrl;

    @Value("${ollama.embed-model}")
    private String embedModel;

    /**
     * Generate a vector embedding for the given text.
     * Returns a float[] of dimension 768 (nomic-embed-text).
     */
    public float[] embed(String text) {
        OllamaEmbedRequest request = new OllamaEmbedRequest(embedModel, text);
        OllamaEmbedResponse response = restTemplate.postForObject(
                baseUrl + "/api/embeddings", request, OllamaEmbedResponse.class);

        if (response == null || response.getEmbedding() == null) {
            throw new RuntimeException("Empty embedding response from Ollama");
        }
        return response.getEmbedding();
    }

    /** Format a float[] as the pgvector literal "[0.1,0.2,...]" */
    public static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
