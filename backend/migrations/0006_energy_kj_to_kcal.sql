-- Health Auto Export sent active and basal energy in kJ and they were stored
-- as-is, while scoring and insights read energy as kcal (about 4.2x too high
-- since 2026-06-19). Convert once; the unit change makes a re-run a no-op.
UPDATE health_records SET value = value / 4.184, unit = 'kcal' WHERE unit = 'kJ';
