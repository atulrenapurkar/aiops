ALTER TABLE application_config
    ADD COLUMN IF NOT EXISTS upstream_projects    JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS downstream_projects  JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS git_repository_urls  JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS confluence_base_url  VARCHAR(500),
    ADD COLUMN IF NOT EXISTS confluence_username  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS confluence_api_key   VARCHAR(500),
    ADD COLUMN IF NOT EXISTS teams_api_key        VARCHAR(500);
