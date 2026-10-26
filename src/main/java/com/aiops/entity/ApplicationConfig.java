package com.aiops.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "application_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Names of other projects this one depends on (calls into). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> upstreamProjects = new ArrayList<>();

    /** Names of other projects that depend on this one. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> downstreamProjects = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> gitRepositoryUrls = new ArrayList<>();

    /** Specific Confluence page URLs to pull (fetched by page ID, not a whole space). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> confluenceUrls = new ArrayList<>();

    private String confluenceBaseUrl;
    private String confluenceUsername;
    private String confluenceApiKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> teamsChannelUrls = new ArrayList<>();

    /** Pre-issued MS Graph bearer token, used directly in place of the global OAuth app. */
    private String teamsApiKey;

    /** Mailbox all project-related email communications are pulled from. */
    private String emailAddress;

    @Column(nullable = false)
    @Builder.Default
    private String status = "CONFIGURED";

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime lastSyncedAt;
}
