package com.aiops.controller;

import com.aiops.service.ingestion.ConfluenceIngestionService;
import com.aiops.service.ingestion.OutlookIngestionService;
import com.aiops.service.ingestion.TeamsIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
@RequiredArgsConstructor
public class IngestionController {

    private final ConfluenceIngestionService confluenceIngestionService;
    private final TeamsIngestionService teamsIngestionService;
    private final OutlookIngestionService outlookIngestionService;

    @PostMapping("/confluence")
    public ResponseEntity<Map<String, Object>> ingestConfluence() {
        int count = confluenceIngestionService.ingest();
        return ResponseEntity.ok(Map.of("source", "CONFLUENCE", "chunksStored", count));
    }

    @PostMapping("/teams")
    public ResponseEntity<Map<String, Object>> ingestTeams() {
        int count = teamsIngestionService.ingest();
        return ResponseEntity.ok(Map.of("source", "TEAMS", "chunksStored", count));
    }

    @PostMapping("/outlook")
    public ResponseEntity<Map<String, Object>> ingestOutlook() {
        int count = outlookIngestionService.ingest();
        return ResponseEntity.ok(Map.of("source", "OUTLOOK", "chunksStored", count));
    }

    @PostMapping("/all")
    public ResponseEntity<Map<String, Object>> ingestAll() {
        int confluence = confluenceIngestionService.ingest();
        int teams      = teamsIngestionService.ingest();
        int outlook    = outlookIngestionService.ingest();
        return ResponseEntity.ok(Map.of(
                "confluence", confluence,
                "teams", teams,
                "outlook", outlook,
                "total", confluence + teams + outlook));
    }
}
