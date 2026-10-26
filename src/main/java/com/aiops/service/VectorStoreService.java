package com.aiops.service;

import com.aiops.entity.KnowledgeChunk;
import com.aiops.entity.SourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private final JdbcTemplate jdbcTemplate;
    private final OllamaService ollamaService;

    /**
     * Embed text and persist a knowledge chunk with its vector.
     */
    public void store(SourceType source, String sourceId, String title, String content, String metadata) {
        store(source, sourceId, title, content, metadata, null);
    }

    /** Like {@link #store} but tags the chunk with the project it was ingested for. */
    public void store(SourceType source, String sourceId, String title, String content, String metadata, Long applicationId) {
        float[] embedding = ollamaService.embed(content);
        String vectorLiteral = OllamaService.toVectorLiteral(embedding);

        jdbcTemplate.update("""
                INSERT INTO knowledge_chunk (source, source_id, title, content, embedding, metadata, application_id, created_at, updated_at)
                VALUES (?::varchar, ?, ?, ?, ?::vector, ?::jsonb, ?, ?, ?)
                ON CONFLICT (source, source_id, (COALESCE(application_id, -1)))
                DO UPDATE SET
                    title = EXCLUDED.title,
                    content = EXCLUDED.content,
                    embedding = EXCLUDED.embedding,
                    metadata = EXCLUDED.metadata,
                    updated_at = EXCLUDED.updated_at
                """,
                source.name(), sourceId, title, content, vectorLiteral,
                metadata, applicationId, LocalDateTime.now(), LocalDateTime.now());

        log.debug("Stored chunk: source={} sourceId={} applicationId={}", source, sourceId, applicationId);
    }

    /**
     * Remove every chunk of one uploaded document. Chunk source_ids are "{documentKey}#{n}",
     * so the part before '#' identifies the document.
     */
    public int deleteDocument(Long applicationId, SourceType source, String documentKey) {
        return jdbcTemplate.update("""
                DELETE FROM knowledge_chunk
                WHERE application_id = ? AND source = ? AND split_part(source_id, '#', 1) = ?
                """,
                applicationId, source.name(), documentKey);
    }

    /**
     * Find the top-k most semantically similar chunks to the query text.
     */
    public List<KnowledgeChunk> similaritySearch(String queryText, int topK) {
        return similaritySearch(queryText, topK, List.of());
    }

    /**
     * Like {@link #similaritySearch(String, int)} but restricted to chunks owned by the given
     * projects. An empty list searches everything.
     */
    public List<KnowledgeChunk> similaritySearch(String queryText, int topK, List<Long> applicationIds) {
        float[] queryEmbedding = ollamaService.embed(queryText);
        String vectorLiteral = OllamaService.toVectorLiteral(queryEmbedding);

        if (applicationIds.isEmpty()) {
            return jdbcTemplate.query("""
                    SELECT id, source, source_id, application_id, title, content, metadata, created_at, updated_at,
                           1 - (embedding <=> ?::vector) AS similarity
                    FROM knowledge_chunk
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """,
                    this::mapRow,
                    vectorLiteral, vectorLiteral, topK);
        }

        // Filter first, then rank exactly. Letting the IVFFlat index rank before the WHERE is
        // applied only probes one list, which can silently drop most of a project's chunks.
        String placeholders = String.join(",", applicationIds.stream().map(id -> "?").toList());
        Object[] args = new Object[applicationIds.size() + 2];
        for (int i = 0; i < applicationIds.size(); i++) args[i] = applicationIds.get(i);
        args[applicationIds.size()] = vectorLiteral;
        args[applicationIds.size() + 1] = topK;

        return jdbcTemplate.query("""
                WITH scoped AS MATERIALIZED (
                    SELECT * FROM knowledge_chunk WHERE application_id IN (%s)
                )
                SELECT id, source, source_id, application_id, title, content, metadata, created_at, updated_at
                FROM scoped
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(placeholders),
                this::mapRow,
                args);
    }

    private KnowledgeChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        return KnowledgeChunk.builder()
                .id(rs.getLong("id"))
                .source(SourceType.valueOf(rs.getString("source")))
                .sourceId(rs.getString("source_id"))
                .applicationId(rs.getObject("application_id", Long.class))
                .title(rs.getString("title"))
                .content(rs.getString("content"))
                .metadata(rs.getString("metadata"))
                .createdAt(rs.getObject("created_at", LocalDateTime.class))
                .updatedAt(rs.getObject("updated_at", LocalDateTime.class))
                .build();
    }
}
