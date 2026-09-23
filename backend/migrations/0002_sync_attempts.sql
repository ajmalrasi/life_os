-- Stage 3 (sync visibility): tracks attempted-not-just-successful mail syncs
-- per source, so a failure (e.g. invalid_grant) is visible even though it
-- never advances a cursor. create_all makes the table on a fresh database;
-- this file exists so an already-deployed database picks it up too.
CREATE TABLE IF NOT EXISTS sync_attempts (
    source varchar(64) PRIMARY KEY,
    last_attempt_at timestamptz NULL,
    last_success_at timestamptz NULL,
    last_error varchar(500) NULL
);
