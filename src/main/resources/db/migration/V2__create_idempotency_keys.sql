-- V2: idempotency keys.
--
-- The (scope, key) pair is the primary key, so a concurrent replay can
-- never store two responses for one key. A row is written in the same
-- transaction as the order or payment it records, so a stored response
-- always has the real resource behind it. request_hash is the SHA-256
-- of the canonical request JSON; a replay with a different payload
-- (different hash) is a 422, not a silent replay.

CREATE TABLE IF NOT EXISTS idempotency_keys (
    scope VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_body VARCHAR(8192) NOT NULL,
    status_code INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scope, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_idempotency_keys_created_at ON idempotency_keys (created_at);
