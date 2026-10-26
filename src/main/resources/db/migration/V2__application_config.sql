CREATE TABLE IF NOT EXISTS application_config (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    confluence_url    VARCHAR(1000),
    teams_channel_url VARCHAR(1000),
    email_address     VARCHAR(500),
    status            VARCHAR(50)  NOT NULL DEFAULT 'CONFIGURED',
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    last_synced_at    TIMESTAMP
);
