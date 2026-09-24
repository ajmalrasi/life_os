-- Mentor Insights (Stage 3): synthesized insights gain a concrete action and
-- a severity so the UI can rank and label them.
-- create_all handles a fresh database, and this file exists so an
-- already-deployed database picks up the new columns too.
ALTER TABLE synthesized_insights ADD COLUMN IF NOT EXISTS action VARCHAR(300);
ALTER TABLE synthesized_insights ADD COLUMN IF NOT EXISTS severity VARCHAR(8);
