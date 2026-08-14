-- Provider registry table backing the persistent provider registry (REQUIREMENTS.md §11.8).
-- One row per provider the facade routes to; columns mirror ProviderEntity / ProviderConfig.
-- Standard SQL: runs on PostgreSQL and on H2 in PostgreSQL-compatibility mode (tests).
CREATE TABLE provider (
    provider_key     VARCHAR(255)  NOT NULL PRIMARY KEY,
    tmforum_base_url VARCHAR(2048) NOT NULL,
    self_description VARCHAR(2048)
);
