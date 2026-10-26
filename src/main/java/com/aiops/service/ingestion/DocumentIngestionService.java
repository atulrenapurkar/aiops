package com.aiops.service.ingestion;

import com.aiops.entity.SourceType;
import com.aiops.service.VectorStoreService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns files and pasted text added to a project (Confluence exports, Word/PDF docs,
 * .eml/.msg emails, Teams chat exports) into embedded knowledge chunks.
 *
 * Each document is split into overlapping chunks so long files stay searchable — embedding a
 * 40-page PDF as one vector would blur every detail in it. Chunks are stored with source_id
 * "{documentKey}#{n}"; re-adding a document with the same name replaces all of its old chunks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final int CHUNK_SIZE = 1500;
    private static final int CHUNK_OVERLAP = 200;
    private static final int MAX_KEY_NAME_LENGTH = 400;

    private final VectorStoreService vectorStoreService;
    private final ObjectMapper objectMapper;
    private final Tika tika = new Tika();

    /** Extract text from any supported file format and store it for the project. Returns chunks stored. */
    public int ingestFile(Long applicationId, SourceType source, String fileName, byte[] bytes) throws IOException {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        String text;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            text = tika.parseToString(in, metadata, -1);
        } catch (TikaException e) {
            throw new IOException("Could not read text from " + fileName + ": " + e.getMessage(), e);
        }
        return ingestText(applicationId, source, fileName, text);
    }

    /** Store already-extracted text (e.g. a Teams thread pasted into the UI). Returns chunks stored. */
    public int ingestText(Long applicationId, SourceType source, String title, String text) {
        String clean = normalize(text);
        if (clean.isBlank()) return 0;

        String documentKey = documentKey(source, title);
        vectorStoreService.deleteDocument(applicationId, source, documentKey);

        List<String> chunks = split(clean);
        for (int i = 0; i < chunks.size(); i++) {
            vectorStoreService.store(source, documentKey + "#" + i, title, chunks.get(i),
                    metadataJson(applicationId, title, i, chunks.size()), applicationId);
        }
        log.info("Ingested '{}' as {} for application {}: {} chunk(s)", title, source, applicationId, chunks.size());
        return chunks.size();
    }

    /** Stable per-document id: same source + name means the same document. '#' is reserved as the chunk separator. */
    public static String documentKey(SourceType source, String name) {
        String safe = name == null ? "untitled" : name.replace('#', '_').trim();
        if (safe.length() > MAX_KEY_NAME_LENGTH) safe = safe.substring(0, MAX_KEY_NAME_LENGTH);
        return "doc:" + source.name() + ":" + safe;
    }

    private String metadataJson(Long applicationId, String title, int part, int totalParts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationId", applicationId);
        m.put("fileName", title);
        m.put("part", part + 1);
        m.put("totalParts", totalParts);
        m.put("uploaded", true);
        try {
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n")
                   .replace('\r', '\n')
                   .replace('\t', ' ')
                   .replaceAll("[ \\u00A0]{2,}", " ")
                   .replaceAll(" *\n *", "\n")
                   .replaceAll("\n{3,}", "\n\n")
                   .trim();
    }

    /** Fixed-size windows with overlap, nudged back to a paragraph or sentence break when one is close. */
    static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            if (end < text.length()) {
                int lastBreak = Math.max(text.lastIndexOf("\n\n", end), text.lastIndexOf(". ", end));
                if (lastBreak > start + CHUNK_SIZE / 2) end = lastBreak + 1;
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
            if (end >= text.length()) break;
            start = end - CHUNK_OVERLAP;
        }
        return chunks;
    }
}
