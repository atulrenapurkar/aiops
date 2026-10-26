package com.aiops.demo;

import com.aiops.entity.KnowledgeChunk;
import com.aiops.entity.SourceType;
import com.aiops.repository.KnowledgeChunkRepository;
import com.aiops.service.ActionSuggestionService;
import com.aiops.service.llm.LlmClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    private final ActionSuggestionService actionSuggestionService;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final DemoDataSeeder demoDataSeeder;

    /** Clear all knowledge chunks and re-seed from DemoDataSeeder */
    @PostMapping("/reseed")
    public ResponseEntity<Map<String, Object>> reseed() {
        log.info("Manual reseed triggered via API");
        int count = demoDataSeeder.reseed();
        return ResponseEntity.ok(Map.of("status", "reseeded", "chunksStored", count));
    }

    /** Sample errors the demo UI lets users pick from */
    @GetMapping("/sample-errors")
    public ResponseEntity<List<Map<String, String>>> sampleErrors() {
        return ResponseEntity.ok(List.of(
                Map.of(
                        "id", "err-0",
                        "label", "Missing Mandatory Field – payment_id (ABC Order Service)",
                        "error", "PaymentValidationException: payment_id is required but was null/missing. " +
                                 "Source System: ORDER-SVC-v2 (ABC Order Service). " +
                                 "Message ID: MSG-98234. " +
                                 "Received fields: order_id=ORD-98234, customer_id=cust_00293, amount=250.00, currency=USD. " +
                                 "Missing mandatory fields: payment_id. " +
                                 "Timestamp: 2024-06-07T10:23:45Z"
                ),
                Map.of(
                        "id", "err-1",
                        "label", "NullPointerException – Payment Service",
                        "error", "FATAL NullPointerException at com.company.payment.PaymentService.processPayment(PaymentService.java:142): " +
                                 "payment_id is null. Order ID: ORD-98234. Customer: cust_00293. " +
                                 "Stack: PaymentService.processPayment -> StripeGateway.charge -> OrderController.checkout"
                ),
                Map.of(
                        "id", "err-2",
                        "label", "HikariCP – DB Connection Pool Timeout",
                        "error", "ERROR HikariPool-1 - Connection is not available, request timed out after 30000ms. " +
                                 "Total connections: 10, Active: 10, Idle: 0, Waiting: 47. " +
                                 "Service: payment-service, Pod: payment-service-7d4f9b-xk2pq, Namespace: payments"
                ),
                Map.of(
                        "id", "err-3",
                        "label", "Stripe 429 – Rate Limit Exceeded",
                        "error", "ERROR StripeGatewayService - Stripe API error: 429 Too Many Requests. " +
                                 "Rate limit: 80 req/s exceeded. Affected transactions: 234 in last 60s. " +
                                 "Retries exhausted after 3 attempts. Orders failing: ORD-98310 through ORD-98544"
                ),
                Map.of(
                        "id", "err-4",
                        "label", "OutOfMemoryError – Order Processing Service",
                        "error", "java.lang.OutOfMemoryError: Java heap space at java.util.ArrayList.<init>(ArrayList.java:153). " +
                                 "Service: order-processing-service. JAVA_OPTS: -Xmx512m. " +
                                 "Pod OOMKilled on gke-prod-2. Method: OrderService.findAllOrders() loaded 198,432 records without pagination."
                )
        ));
    }

    /**
     * Run RAG pipeline on the submitted error log or question and return the answer.
     * With applicationId set, only that project's (and its dependencies') knowledge is searched.
     */
    @PostMapping("/simulate-error")
    public ResponseEntity<?> simulateError(@RequestBody SimulateRequest request) {
        if (request.getErrorMessage() == null || request.getErrorMessage().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "errorMessage is required"));
        }
        long start = System.currentTimeMillis();
        ActionSuggestionService.SuggestionResult result;
        try {
            result = actionSuggestionService.suggest(request.getErrorMessage(), request.getApplicationId());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        long elapsed = System.currentTimeMillis() - start;

        // One entry per distinct document, in relevance order
        Map<String, SourceRef> refs = new LinkedHashMap<>();
        for (KnowledgeChunk c : result.sources()) {
            refs.putIfAbsent(c.getSource() + "|" + c.getTitle(), new SourceRef(
                    c.getSource().name(), c.getTitle(),
                    c.getApplicationId() == null ? null : result.projectNames().get(c.getApplicationId())));
        }

        return ResponseEntity.ok(new SimulateResponse(
                request.getErrorMessage(), result.answer(), elapsed, new ArrayList<>(refs.values())));
    }

    /**
     * Generate a structured service summary from all knowledge base chunks using Claude.
     * Returns a JSON array of service objects with input/flow/output/exception fields.
     */
    @GetMapping("/knowledge-summary")
    public ResponseEntity<List<Map<String, Object>>> knowledgeSummary() {
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findAll();
        if (chunks.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // Trim each chunk to 900 chars so the total prompt stays within Claude's input window
        String context = chunks.stream()
                .map(c -> {
                    String content = c.getContent();
                    if (content != null && content.length() > 900) {
                        content = content.substring(0, 900) + "... [truncated]";
                    }
                    return "[" + c.getSource() + "] " + c.getTitle() + ":\n" + content;
                })
                .collect(java.util.stream.Collectors.joining("\n\n---\n\n"));

        String system = "You are a technical architect. Return ONLY a valid JSON array. No markdown, no explanation, no code fences. Never wrap in ```json. inputMessage and outputMessage must be JSON OBJECTS (not strings).";

        String user = """
                Analyze the knowledge base below and extract a detailed summary for each distinct web service.
                For each service, identify every API endpoint and document it.

                Return a JSON array where each element has EXACTLY this structure:
                {
                  "serviceName": "Payment Service",
                  "description": "Handles payment processing and charging",
                  "sources": ["CONFLUENCE"],
                  "endpoints": [
                    {
                      "method": "POST",
                      "path": "/api/payments/charge",
                      "description": "Charge a customer for an order",
                      "inputMessage": {
                        "payment_id": "PAY-12345",
                        "order_id": "ORD-98234",
                        "customer_id": "cust_00293",
                        "amount": 250.00,
                        "currency": "USD",
                        "message_id": "MSG-98234",
                        "source_system": "ORDER-SVC-v2"
                      },
                      "mandatoryFields": ["payment_id", "order_id", "customer_id", "amount", "currency"],
                      "optionalFields": ["message_id", "source_system", "idempotency_key"],
                      "normalFlow": [
                        "Step 1: Validate mandatory fields",
                        "Step 2: Look up customer",
                        "Step 3: Call payment gateway",
                        "Step 4: Record transaction",
                        "Step 5: Return success response"
                      ],
                      "outputMessage": {
                        "status": "success",
                        "transaction_id": "TXN-56789",
                        "payment_id": "PAY-12345",
                        "amount_charged": 250.00,
                        "currency": "USD",
                        "timestamp": "2024-06-07T10:23:45Z"
                      },
                      "exceptionFlow": [
                        {
                          "exception": "PaymentValidationException",
                          "cause": "Mandatory field payment_id is missing",
                          "httpStatus": "400",
                          "correctiveAction": "Contact ABC Order Service (ORDER-SVC-v2): For message MSG-98234, payment_id was not sent. Please ensure payment_id is included in all charge requests."
                        }
                      ]
                    }
                  ]
                }

                Rules:
                - sources array values must only be from: CONFLUENCE, TEAMS, OUTLOOK
                - inputMessage and outputMessage must be real JSON OBJECTS with actual field names and example values — NOT strings
                - mandatoryFields and optionalFields are string arrays of field names only
                - exceptionFlow correctiveAction must name the source system, the field, and exact fix
                - If a section has no info, use empty array [] or empty object {}
                - Extract ALL endpoints mentioned across Wiki, Teams, and emails

                Knowledge Base:
                """ + context;

        try {
            String raw = llmClient.chat(system, user, 8000).trim();
            // Strip markdown code fences if Claude added them despite the instruction
            if (raw.startsWith("```")) {
                raw = raw.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```\\s*$", "").trim();
            }
            List<Map<String, Object>> summaries = objectMapper.readValue(
                    raw, new TypeReference<>() {});
            return ResponseEntity.ok(summaries);
        } catch (Exception e) {
            log.error("Failed to parse knowledge summary JSON: {}", e.getMessage());
            return ResponseEntity.ok(List.of(Map.of("error", "Summary generation failed: " + e.getMessage())));
        }
    }

    /** List all knowledge base chunks grouped by source */
    @GetMapping("/knowledge-base")
    public ResponseEntity<Map<String, List<KnowledgeChunk>>> knowledgeBase() {
        return ResponseEntity.ok(Map.of(
                "confluence", knowledgeChunkRepository.findBySource(SourceType.CONFLUENCE),
                "teams",      knowledgeChunkRepository.findBySource(SourceType.TEAMS),
                "outlook",    knowledgeChunkRepository.findBySource(SourceType.OUTLOOK),
                "code",       knowledgeChunkRepository.findBySource(SourceType.CODE),
                "documents",  knowledgeChunkRepository.findBySource(SourceType.DOCUMENT)
        ));
    }

    @Data
    public static class SimulateRequest {
        private String errorMessage;
        /** Optional — restricts the search to this project and its dependencies. */
        private Long applicationId;
    }

    @Data
    public static class SimulateResponse {
        private final String errorMessage;
        private final String suggestion;
        private final long processingMs;
        private final List<SourceRef> sources;
    }

    public record SourceRef(String source, String title, String project) {}
}
