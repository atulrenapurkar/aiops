package com.aiops.controller;

import com.aiops.entity.ApplicationConfig;
import com.aiops.entity.KnowledgeChunk;
import com.aiops.entity.SourceType;
import com.aiops.repository.ApplicationConfigRepository;
import com.aiops.repository.KnowledgeChunkRepository;
import com.aiops.service.VectorStoreService;
import com.aiops.service.ingestion.ConfluenceIngestionService;
import com.aiops.service.ingestion.DocumentIngestionService;
import com.aiops.service.ingestion.TeamsIngestionService;
import com.aiops.service.ingestion.OutlookIngestionService;
import com.aiops.service.ingestion.RepoIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationConfigController {

    private final ApplicationConfigRepository repository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final ConfluenceIngestionService confluenceIngestionService;
    private final TeamsIngestionService teamsIngestionService;
    private final OutlookIngestionService outlookIngestionService;
    private final RepoIngestionService repoIngestionService;
    private final DocumentIngestionService documentIngestionService;
    private final VectorStoreService vectorStoreService;

    @GetMapping
    public List<ApplicationConfig> list() {
        return repository.findAll();
    }

    @PostMapping
    public ResponseEntity<ApplicationConfig> create(@RequestBody ApplicationConfig config) {
        config.setId(null);
        config.setStatus("CONFIGURED");
        config.setCreatedAt(LocalDateTime.now());
        config.setLastSyncedAt(null);
        sanitizeLists(config);
        return ResponseEntity.ok(repository.save(config));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApplicationConfig> update(@PathVariable Long id,
                                                     @RequestBody ApplicationConfig incoming) {
        return repository.findById(id).map(existing -> {
            existing.setName(incoming.getName());
            existing.setUpstreamProjects(incoming.getUpstreamProjects());
            existing.setDownstreamProjects(incoming.getDownstreamProjects());
            existing.setGitRepositoryUrls(incoming.getGitRepositoryUrls());
            existing.setConfluenceUrls(incoming.getConfluenceUrls());
            existing.setConfluenceBaseUrl(incoming.getConfluenceBaseUrl());
            existing.setConfluenceUsername(incoming.getConfluenceUsername());
            if (incoming.getConfluenceApiKey() != null && !incoming.getConfluenceApiKey().isBlank()) {
                existing.setConfluenceApiKey(incoming.getConfluenceApiKey());
            }
            existing.setTeamsChannelUrls(incoming.getTeamsChannelUrls());
            if (incoming.getTeamsApiKey() != null && !incoming.getTeamsApiKey().isBlank()) {
                existing.setTeamsApiKey(incoming.getTeamsApiKey());
            }
            existing.setEmailAddress(incoming.getEmailAddress());
            sanitizeLists(existing);
            return ResponseEntity.ok(repository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Confluence documents, Teams messages, and emails ingested for this project so far. */
    @GetMapping("/{id}/knowledge")
    public ResponseEntity<Map<String, List<KnowledgeChunk>>> knowledge(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, List<KnowledgeChunk>> result = new LinkedHashMap<>();
        result.put("confluence", knowledgeChunkRepository.findByApplicationIdAndSource(id, SourceType.CONFLUENCE));
        result.put("teams", knowledgeChunkRepository.findByApplicationIdAndSource(id, SourceType.TEAMS));
        result.put("outlook", knowledgeChunkRepository.findByApplicationIdAndSource(id, SourceType.OUTLOOK));
        result.put("code", knowledgeChunkRepository.findByApplicationIdAndSource(id, SourceType.CODE));
        result.put("documents", knowledgeChunkRepository.findByApplicationIdAndSource(id, SourceType.DOCUMENT));
        return ResponseEntity.ok(result);
    }

    /** Upload a downloaded Confluence page export for this project. Kept for older clients; same as /documents with source=CONFLUENCE. */
    @PostMapping(value = "/{id}/confluence/upload")
    public ResponseEntity<?> uploadConfluencePage(@PathVariable Long id,
                                                   @RequestParam("file") MultipartFile file) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = ingestOne(id, SourceType.CONFLUENCE, file);
        return result.containsKey("error")
                ? ResponseEntity.unprocessableEntity().body(result)
                : ResponseEntity.ok(result);
    }

    /**
     * Add knowledge files to a project: Confluence exports, emails (.eml/.msg), Teams chat exports,
     * or any other document. PDF, Word, HTML, Markdown and plain text are all accepted.
     * Re-uploading a file with the same name and source replaces the earlier version.
     */
    @PostMapping("/{id}/documents")
    public ResponseEntity<?> uploadDocuments(@PathVariable Long id,
                                             @RequestParam("source") String source,
                                             @RequestParam("files") List<MultipartFile> files) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        SourceType sourceType = parseUploadSource(source);
        if (sourceType == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "source must be one of " + UPLOAD_SOURCES));
        }
        List<Map<String, Object>> results = files.stream()
                .map(f -> ingestOne(id, sourceType, f))
                .toList();
        return ResponseEntity.ok(Map.of("results", results));
    }

    /** Add pasted text (e.g. a Teams thread or an email body copied from the client) to a project. */
    @PostMapping("/{id}/documents/text")
    public ResponseEntity<?> addText(@PathVariable Long id, @RequestBody TextDocumentRequest request) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        SourceType sourceType = parseUploadSource(request.source());
        if (sourceType == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "source must be one of " + UPLOAD_SOURCES));
        }
        if (request.title() == null || request.title().isBlank() || request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title and text are required"));
        }
        int chunks = documentIngestionService.ingestText(id, sourceType, request.title().trim(), request.text());
        return ResponseEntity.ok(Map.of("fileName", request.title().trim(), "chunks", chunks));
    }

    /** Remove one document (all of its chunks) from a project. documentKey is a chunk's source_id up to the '#'. */
    @DeleteMapping("/{id}/documents")
    public ResponseEntity<?> deleteDocument(@PathVariable Long id,
                                            @RequestParam("source") String source,
                                            @RequestParam("documentKey") String documentKey) {
        SourceType sourceType;
        try {
            sourceType = SourceType.valueOf(source.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown source: " + source));
        }
        int deleted = vectorStoreService.deleteDocument(id, sourceType, documentKey);
        return ResponseEntity.ok(Map.of("deletedChunks", deleted));
    }

    private Map<String, Object> ingestOne(Long applicationId, SourceType source, MultipartFile file) {
        String fileName = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "upload" : file.getOriginalFilename();
        try {
            int chunks = documentIngestionService.ingestFile(applicationId, source, fileName, file.getBytes());
            if (chunks == 0) {
                return Map.of("fileName", fileName, "error", "No readable text found in the file");
            }
            return Map.of("fileName", fileName, "chunks", chunks, "stored", chunks);
        } catch (Exception e) {
            log.warn("Failed to ingest {} for application {}: {}", fileName, applicationId, e.getMessage());
            return Map.of("fileName", fileName, "error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static final List<SourceType> UPLOAD_SOURCES =
            List.of(SourceType.CONFLUENCE, SourceType.OUTLOOK, SourceType.TEAMS, SourceType.DOCUMENT);

    /** Source types a user may upload into. CODE is only produced by repository sync. */
    private static SourceType parseUploadSource(String source) {
        if (source == null) return null;
        try {
            SourceType t = SourceType.valueOf(source.trim().toUpperCase());
            return UPLOAD_SOURCES.contains(t) ? t : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record TextDocumentRequest(String source, String title, String text) {}

    /** Trigger ingestion for all configured sources of this application, using its own credentials when set. */
    @PostMapping("/{id}/sync")
    public ResponseEntity<Map<String, Object>> sync(@PathVariable Long id) {
        ApplicationConfig config = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Application not found: " + id));

        config.setStatus("SYNCING");
        repository.save(config);

        int confluence = 0, teams = 0, outlook = 0, code = 0;
        String error = null;

        try {
            if (!config.getConfluenceUrls().isEmpty()) {
                confluence = confluenceIngestionService.ingestPages(
                        config.getConfluenceUrls(),
                        config.getConfluenceBaseUrl(),
                        config.getConfluenceUsername(),
                        config.getConfluenceApiKey(),
                        config.getId());
            }
            if (!config.getTeamsChannelUrls().isEmpty()) {
                teams = teamsIngestionService.ingest(config.getTeamsApiKey(), config.getId());
            }
            if (config.getEmailAddress() != null && !config.getEmailAddress().isBlank()) {
                outlook = outlookIngestionService.ingest(config.getEmailAddress(), config.getId());
            }
            if (!config.getGitRepositoryUrls().isEmpty()) {
                code = repoIngestionService.ingestRepos(config.getGitRepositoryUrls(), config.getId());
            }
            config.setStatus("SYNCED");
        } catch (Exception e) {
            log.error("Sync failed for application {}: {}", config.getName(), e.getMessage());
            config.setStatus("ERROR");
            error = e.getMessage();
        }

        config.setLastSyncedAt(LocalDateTime.now());
        repository.save(config);

        return ResponseEntity.ok(Map.of(
                "status", config.getStatus(),
                "confluence", confluence,
                "teams", teams,
                "outlook", outlook,
                "code", code,
                "total", confluence + teams + outlook + code,
                "error", error != null ? error : ""
        ));
    }

    /** Remove null/blank entries from every list field before persisting */
    private void sanitizeLists(ApplicationConfig config) {
        config.setUpstreamProjects(cleanList(config.getUpstreamProjects()));
        config.setDownstreamProjects(cleanList(config.getDownstreamProjects()));
        config.setGitRepositoryUrls(cleanList(config.getGitRepositoryUrls()));
        config.setConfluenceUrls(cleanList(config.getConfluenceUrls()));
        config.setTeamsChannelUrls(cleanList(config.getTeamsChannelUrls()));
    }

    private List<String> cleanList(List<String> list) {
        if (list == null) return new ArrayList<>();
        return list.stream().filter(s -> s != null && !s.isBlank()).toList();
    }
}
