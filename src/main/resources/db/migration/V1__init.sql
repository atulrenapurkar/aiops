-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Knowledge chunks from Teams, Outlook, Confluence
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id          BIGSERIAL PRIMARY KEY,
    source      VARCHAR(50)  NOT NULL,          -- TEAMS | OUTLOOK | CONFLUENCE
    source_id   VARCHAR(500),                   -- original ID in source system
    title       TEXT,
    content     TEXT         NOT NULL,
    embedding   vector(768),                    -- nomic-embed-text produces 768 dims
    metadata    JSONB,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- IVFFlat index for fast approximate nearest-neighbour search
CREATE INDEX IF NOT EXISTS knowledge_chunk_embedding_idx
    ON knowledge_chunk USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- Alert logs received from Datadog
CREATE TABLE IF NOT EXISTS alert_log (
    id           BIGSERIAL PRIMARY KEY,
    datadog_id   VARCHAR(200) UNIQUE,
    service      VARCHAR(200),
    message      TEXT         NOT NULL,
    severity     VARCHAR(50),
    tags         JSONB,
    suggestion   TEXT,
    processed_at TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);
