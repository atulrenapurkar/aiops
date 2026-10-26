package com.aiops.demo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@Slf4j
@Component
@ConditionalOnProperty(name = "demo.enabled", havingValue = "true")
public class WireMockStubServer {

    @Value("${wiremock.port:8089}")
    private int port;

    private WireMockServer server;

    @PostConstruct
    public void start() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().port(port));
        server.start();
        registerStubs();
        log.info("WireMock stub server started on port {} (Confluence + Teams + Outlook)", port);
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private void registerStubs() {
        stubMicrosoftAuth();
        stubConfluence();
        stubTeams();
        stubOutlook();
    }

    // ── Microsoft OAuth token ────────────────────────────────────────────────

    private void stubMicrosoftAuth() {
        server.stubFor(post(urlPathMatching(".*/oauth2/v2.0/token"))
                .willReturn(okJson("""
                        {
                          "access_token": "wiremock-fake-token",
                          "token_type": "Bearer",
                          "expires_in": 3600
                        }
                        """)));
    }

    // ── Confluence pages ─────────────────────────────────────────────────────

    private void stubConfluence() {
        server.stubFor(get(urlPathEqualTo("/rest/api/content"))
                .willReturn(okJson("""
                        {
                          "results": [
                            {
                              "id": "wiki-stub-001",
                              "title": "Incident Response Playbook",
                              "body": {
                                "storage": {
                                  "value": "<p>When a critical incident occurs: 1. Page the on-call engineer via PagerDuty. 2. Create a war-room channel in Teams. 3. Assign incident commander. 4. Post updates every 15 minutes. 5. Run post-mortem within 48 hours.</p>"
                                }
                              }
                            },
                            {
                              "id": "wiki-stub-002",
                              "title": "Microservices Dependency Map",
                              "body": {
                                "storage": {
                                  "value": "<p>Order Service depends on: Payment Service (REST), Inventory Service (gRPC), Notification Service (async/Kafka). Payment Service depends on: Stripe API, PostgreSQL (HikariCP pool max=50), Redis (session cache). If Payment Service is down, Order Service falls back to queuing orders in Kafka topic orders.pending.</p>"
                                }
                              }
                            },
                            {
                              "id": "wiki-stub-003",
                              "title": "On-Call Runbook: Database Issues",
                              "body": {
                                "storage": {
                                  "value": "<p>Connection pool exhaustion: run SELECT count(*), state FROM pg_stat_activity GROUP BY state to check active connections. Kill idle-in-transaction sessions older than 5 min: SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE state='idle in transaction' AND query_start < now() - interval '5 minutes'. Increase pool size in configmap and restart pods rolling.</p>"
                                }
                              }
                            }
                          ],
                          "_links": {}
                        }
                        """)));
    }

    // ── MS Teams messages ─────────────────────────────────────────────────────

    private void stubTeams() {
        server.stubFor(get(urlPathMatching(".*/teams/.*/channels/.*/messages"))
                .willReturn(okJson("""
                        {
                          "value": [
                            {
                              "id": "teams-stub-001",
                              "body": {
                                "content": "ALERT: Payment service pod payment-service-7d4f9b restarted 3 times in last 10 minutes. OOMKilled. Heap dump saved to /tmp/heapdump.hprof on node gke-prod-3."
                              },
                              "from": { "user": { "displayName": "DevOps Bot" } },
                              "createdDateTime": "2024-06-05T14:00:00Z"
                            },
                            {
                              "id": "teams-stub-002",
                              "body": {
                                "content": "Fixed the OOM: bumped JAVA_OPTS to -Xmx2g and added Pageable to OrderService.findAllOrders(). Deploying v2.4.1 now. Rolling update in progress."
                              },
                              "from": { "user": { "displayName": "Sarah Chen" } },
                              "createdDateTime": "2024-06-05T14:45:00Z"
                            },
                            {
                              "id": "teams-stub-003",
                              "body": {
                                "content": "Stripe rate limit hit again during flash sale. 429 errors spiking. Enabling request queue with STRIPE_QUEUE_CAPACITY=500 as per runbook. ETA 5 min."
                              },
                              "from": { "user": { "displayName": "Raj Patel" } },
                              "createdDateTime": "2024-06-06T09:15:00Z"
                            }
                          ]
                        }
                        """)));
    }

    // ── Outlook emails ────────────────────────────────────────────────────────

    private void stubOutlook() {
        server.stubFor(get(urlPathMatching(".*/users/.*/messages"))
                .willReturn(okJson("""
                        {
                          "value": [
                            {
                              "id": "outlook-stub-001",
                              "subject": "ACTION REQUIRED: Payment Service SLA Breach",
                              "from": { "emailAddress": { "address": "monitoring@company.com" } },
                              "receivedDateTime": "2024-06-05T13:00:00Z",
                              "body": {
                                "content": "Payment Service p99 latency exceeded 5s SLA for 15 consecutive minutes. Current p99: 8.2s. Affected region: us-east-1. Recommend scaling payment-service to 6 replicas and investigating DB query performance. Runbook: https://company.atlassian.net/wiki/payment-sla"
                              }
                            },
                            {
                              "id": "outlook-stub-002",
                              "subject": "Re: HikariCP Pool Size Recommendations",
                              "from": { "emailAddress": { "address": "platform-eng@company.com" } },
                              "receivedDateTime": "2024-06-04T11:30:00Z",
                              "body": {
                                "content": "After load testing at 1000 rps, recommended HikariCP settings for payment-service: maximum-pool-size=75, minimum-idle=15, connection-timeout=30000, idle-timeout=600000, max-lifetime=1800000. Apply to payments-configmap.yaml and roll deployment."
                              }
                            },
                            {
                              "id": "outlook-stub-003",
                              "subject": "Postmortem: June 5 Payment Outage",
                              "from": { "emailAddress": { "address": "engineering@company.com" } },
                              "receivedDateTime": "2024-06-06T10:00:00Z",
                              "body": {
                                "content": "Root cause: OOMKilled pods caused by unpaginated OrderService.findAllOrders() loading 200k+ rows. Fix applied: added Pageable parameter, bumped -Xmx to 2g, memory limit to 4Gi. Future prevention: add automated heap analysis to CI pipeline, enforce Pageable in code review checklist."
                              }
                            }
                          ]
                        }
                        """)));
    }
}
