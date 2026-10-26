package com.aiops.service;

import com.aiops.entity.AlertLog;
import com.aiops.entity.ApplicationConfig;
import com.aiops.entity.KnowledgeChunk;
import com.aiops.repository.AlertLogRepository;
import com.aiops.repository.ApplicationConfigRepository;
import com.aiops.service.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActionSuggestionService {

    private final VectorStoreService vectorStoreService;
    private final LlmClient llmClient;
    private final AlertLogRepository alertLogRepository;
    private final TeamsNotificationService teamsNotificationService;

    private final ApplicationConfigRepository applicationConfigRepository;

    private static final int TOP_K = 8;

    private static final String SYSTEM_PROMPT = """
            You are an expert SRE (Site Reliability Engineer) and support engineer.
            You will be given input from a user plus context retrieved from the team's
            knowledge base (Confluence documentation, MS Teams chats, emails, uploaded
            documents, and source code).

            The input is either an error / log excerpt (e.g. from Datadog) or a question.

            If it is an error or log:
            1. Identify the root cause based on the context.
            2. Suggest clear, actionable remediation steps (who to contact, what to change, where).
            Format:

            **Root Cause:** <one sentence>

            **Suggested Actions:**
            1. <action>
            2. <action>

            **References:** <list the source titles used>

            If it is a question: answer it directly and concisely, then end with
            **References:** <list the source titles used>

            Use only facts found in the context. If the context does not contain the answer,
            say so plainly and say what information would be needed, instead of guessing.
            """;

    /** A generated answer plus the knowledge chunks it was grounded on. */
    public record SuggestionResult(String answer, List<KnowledgeChunk> sources, Map<Long, String> projectNames) {}

    /**
     * Run RAG pipeline over all knowledge: embed the error → search knowledge base → ask the LLM.
     */
    public String suggest(String errorMessage) {
        return suggest(errorMessage, null).answer();
    }

    /**
     * Run RAG pipeline for one project. Searches the project's own knowledge plus that of its
     * upstream/downstream projects, since errors often originate in a dependency.
     * A null applicationId searches all knowledge.
     */
    public SuggestionResult suggest(String input, Long applicationId) {
        Map<Long, String> projectNames = scopeFor(applicationId);
        List<KnowledgeChunk> context = vectorStoreService.similaritySearch(
                input, TOP_K, new ArrayList<>(projectNames.keySet()));

        if (context.isEmpty()) {
            String msg = applicationId == null
                    ? "No relevant knowledge base entries found. Please ingest data from Teams, Outlook, and Confluence first."
                    : "No knowledge has been added to project \"" + projectNames.get(applicationId) + "\" yet. "
                      + "Open Projects → Details → Add Knowledge to upload documents, emails or Teams chats.";
            return new SuggestionResult(msg, List.of(), projectNames);
        }

        String contextText = context.stream()
                .map(c -> String.format("--- Source: %s | %s%s ---\n%s",
                        c.getSource(), c.getTitle(), projectLabel(c, applicationId, projectNames), c.getContent()))
                .collect(Collectors.joining("\n\n"));

        String projectLine = applicationId == null ? ""
                : "**Project:** " + projectNames.get(applicationId) + "\n\n";

        String userPrompt = String.format("""
                %s**Input (error log or question):**
                %s

                **Knowledge Base Context:**
                %s
                """, projectLine, input, contextText);

        return new SuggestionResult(llmClient.chat(SYSTEM_PROMPT, userPrompt), context, projectNames);
    }

    /** Project id → name for the project and its dependencies; empty means "search everything". */
    private Map<Long, String> scopeFor(Long applicationId) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (applicationId == null) return names;

        ApplicationConfig project = applicationConfigRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + applicationId));
        names.put(project.getId(), project.getName());

        Set<String> related = new HashSet<>(project.getUpstreamProjects());
        related.addAll(project.getDownstreamProjects());
        if (!related.isEmpty()) {
            applicationConfigRepository.findByNameIn(related)
                    .forEach(p -> names.putIfAbsent(p.getId(), p.getName()));
        }
        return names;
    }

    private static String projectLabel(KnowledgeChunk c, Long applicationId, Map<Long, String> projectNames) {
        if (applicationId == null || c.getApplicationId() == null || c.getApplicationId().equals(applicationId)) {
            return "";
        }
        return " | from dependency project " + projectNames.get(c.getApplicationId());
    }

    /**
     * Called by DatadogPollerService — runs RAG and persists the suggestion,
     * then posts it to MS Teams.
     */
    public void suggestAndUpdate(AlertLog alert) {
        try {
            String suggestion = suggest(alert.getMessage());

            alert.setSuggestion(suggestion);
            alert.setProcessedAt(LocalDateTime.now());
            alertLogRepository.save(alert);

            log.info("Suggestion generated for alert {}", alert.getDatadogId());

            // Notify Teams
            teamsNotificationService.postSuggestion(alert, suggestion);

        } catch (Exception e) {
            log.error("Failed to generate suggestion for alert {}: {}", alert.getDatadogId(), e.getMessage(), e);
        }
    }
}
