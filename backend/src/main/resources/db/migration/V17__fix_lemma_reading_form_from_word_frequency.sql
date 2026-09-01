-- design.md §17: lemma.reading_form is first-write-wins per
-- (normalized_form, part_of_speech), captured from whichever surface
-- occurrence was imported first. For a word almost always seen conjugated
-- (いる conjugated as い+た), that first occurrence's reading is the
-- conjugated stem's reading (イ), not the dictionary form's own reading
-- (イル) -- so a join against word_frequency's reading (always dictionary-
-- form, e.g. イル for いる/居る) silently misses.
--
-- Correct existing rows using word_frequency's already-correct, already-
-- deduped (V16) dictionary-form readings. Two tiers, tried in order:
--
-- 1. Exact term match (wf.term = l.dictionary_form): handles kanji with
--    genuinely multiple, unrelated readings sharing one normalized_form --
--    e.g. 居る is both いる/イル (rank 18) and おる/オル (rank 229), so
--    normalized_form alone can't disambiguate; the lemma's own
--    dictionary_form spelling can.
-- 2. Single-reading-per-normalized_form fallback: handles the opposite
--    case -- a script variant (出来る) whose own word_frequency row V16
--    deleted as a duplicate of できる, so no exact term match exists, but
--    the surviving normalized_form has only one real reading anyway.
--
-- Words with genuinely multiple real readings AND no exact term match
-- (生 has roughly eight) are left untouched rather than guessed at.
UPDATE lemma l
SET reading_form = corrected.reading
FROM (
    SELECT l2.id, COALESCE(exact_match.reading, unambiguous.reading) AS reading
    FROM lemma l2
    LEFT JOIN word_frequency exact_match
        ON exact_match.term = l2.dictionary_form AND exact_match.normalized_form = l2.normalized_form
    LEFT JOIN (
        SELECT normalized_form, MIN(reading) AS reading
        FROM word_frequency
        WHERE normalized_form IS NOT NULL
        GROUP BY normalized_form
        HAVING COUNT(DISTINCT reading) = 1
    ) unambiguous ON unambiguous.normalized_form = l2.normalized_form
) corrected
WHERE corrected.id = l.id
  AND corrected.reading IS NOT NULL
  AND l.reading_form IS DISTINCT FROM corrected.reading;
