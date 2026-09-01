-- Collapses word_frequency's script-variant duplicates down to one row per
-- real word (design.md §16) — until now, できる (rank 94) and 出来る (rank
-- 1302) were two separate rows, and every query joining lemma against
-- word_frequency picked up whichever spelling happened to be imported first
-- (lemma.dictionary_form is first-write-wins, per decision #1), silently
-- giving the same word a different difficulty weight, meaning, and status
-- display depending on which spelling appeared in a given text. Real data:
-- 498 duplicate (normalized_form, reading) groups in the actual seeded top
-- 10k, not just できる/出来る — ある/在る/有る, なる/成る, いる/居る,
-- 思う/想う, 言う/云う, ない/無い among them.
--
-- Keeps the best (lowest) rank per group — the more common spelling is the
-- more useful canonical one. Ranks within a real duplicate group are always
-- distinct (derived from a strict total ordering), so there's no tie to
-- break.
DELETE FROM word_frequency wf
USING word_frequency wf2
WHERE wf.normalized_form = wf2.normalized_form
  AND wf.reading = wf2.reading
  AND wf.rank > wf2.rank;

-- Enforces the invariant this migration just established, the same way
-- lemma's own (normalized_form, part_of_speech) uniqueness is a real
-- constraint, not just a convention — and its index is what makes the
-- lemma-to-word_frequency join (TextLibraryRepository,
-- VocabularyBrowseRepository, TextReadRepository) an indexed lookup instead
-- of a sequential scan.
CREATE UNIQUE INDEX word_frequency_normalized_form_reading_idx
    ON word_frequency (normalized_form, reading);
