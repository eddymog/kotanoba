-- Adds part_of_speech to word_frequency so the vocabulary browse page (§13)
-- can show POS for every word, not just ones that already have a lemma row.
-- Nullable: filled by V8's one-time batch resolution, not computed live —
-- see V8's comment for why (the NLP service stays off the read path).
ALTER TABLE word_frequency ADD COLUMN part_of_speech TEXT;
