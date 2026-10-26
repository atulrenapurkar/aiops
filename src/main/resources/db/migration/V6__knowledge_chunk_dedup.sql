-- Remove duplicates accumulated before dedup was enforced, keeping the newest row per group.
-- application_id is nullable (global/demo data), so COALESCE it to a sentinel for grouping —
-- otherwise NULL <> NULL would let every global-scope row "duplicate" past a plain unique index.
DELETE FROM knowledge_chunk kc
USING knowledge_chunk kc2
WHERE kc.source = kc2.source
  AND kc.source_id = kc2.source_id
  AND COALESCE(kc.application_id, -1) = COALESCE(kc2.application_id, -1)
  AND kc.id < kc2.id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_chunk_source_sourceid_app
    ON knowledge_chunk (source, source_id, COALESCE(application_id, -1));
