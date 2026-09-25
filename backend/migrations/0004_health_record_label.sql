-- A workout's activity (e.g. "Traditional Strength Training") is kept in a
-- new nullable label column; plain metric readings leave it empty.
-- create_all handles a fresh database, and this file exists so an
-- already-deployed database picks up the new column too.
ALTER TABLE health_records ADD COLUMN IF NOT EXISTS label VARCHAR(64);
