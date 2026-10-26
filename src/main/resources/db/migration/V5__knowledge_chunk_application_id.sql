ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS application_id BIGINT NULL REFERENCES application_config(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_application_id ON knowledge_chunk(application_id);
