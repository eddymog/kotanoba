-- Adds meaning to word_frequency for the vocabulary browse page (§13).
-- Nullable: filled by V10's one-time batch match against JMdict, not every
-- word gets one — see V10's comment on match coverage (~75.4% overall).
ALTER TABLE word_frequency ADD COLUMN meaning TEXT;
