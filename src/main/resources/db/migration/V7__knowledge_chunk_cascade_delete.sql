-- Deleting a project should remove its ingested knowledge, not orphan it to NULL.
-- SET NULL let two independently-deleted projects' chunks collide on the dedup
-- unique index the moment they happened to share a source_id (e.g. the same
-- repo URL), since NULL was coalesced to the same sentinel for both.
ALTER TABLE knowledge_chunk DROP CONSTRAINT IF EXISTS knowledge_chunk_application_id_fkey;
ALTER TABLE knowledge_chunk
    ADD CONSTRAINT knowledge_chunk_application_id_fkey
    FOREIGN KEY (application_id) REFERENCES application_config(id) ON DELETE CASCADE;
