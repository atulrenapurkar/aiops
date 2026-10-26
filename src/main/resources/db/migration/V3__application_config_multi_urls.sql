-- Add new JSONB array columns
ALTER TABLE application_config
    ADD COLUMN IF NOT EXISTS confluence_urls    JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS teams_channel_urls JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Migrate any existing single-value data into the arrays
UPDATE application_config
SET confluence_urls     = jsonb_build_array(confluence_url)
WHERE confluence_url IS NOT NULL AND confluence_url <> '';

UPDATE application_config
SET teams_channel_urls  = jsonb_build_array(teams_channel_url)
WHERE teams_channel_url IS NOT NULL AND teams_channel_url <> '';

-- Drop the old single-value columns
ALTER TABLE application_config
    DROP COLUMN IF EXISTS confluence_url,
    DROP COLUMN IF EXISTS teams_channel_url;
