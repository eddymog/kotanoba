-- Reading-progress signal for the library page, scoped deliberately small:
-- just a recency timestamp, not exact resume position. Nullable — existing
-- texts, and any text never reopened after import, have no value here,
-- which the library UI treats as "never opened" rather than a placeholder.
ALTER TABLE text ADD COLUMN last_opened_at TIMESTAMPTZ;
