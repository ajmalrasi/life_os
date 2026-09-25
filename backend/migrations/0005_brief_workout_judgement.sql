-- The Today brief also judges recent workouts (duration, energy, heart rate)
-- and flags whether they need attention in Today's "Needs you" card.
-- create_all handles a fresh database, and this file exists so an
-- already-deployed database picks up the new columns too.
ALTER TABLE daily_briefs ADD COLUMN IF NOT EXISTS workout_note VARCHAR(200);
ALTER TABLE daily_briefs ADD COLUMN IF NOT EXISTS workout_attention BOOLEAN NOT NULL DEFAULT FALSE;
