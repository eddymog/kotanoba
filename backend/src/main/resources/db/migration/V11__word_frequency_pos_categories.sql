-- Adds a filterable derived form of part_of_speech (V7/V8) for the
-- vocabulary browse page's POS filter — design.md §13.
--
-- part_of_speech is either a full Sudachi tag ("動詞,一般,*,*,...") or,
-- for multi-morpheme entries jpdb counts as one frequency unit
-- ("には" -> "助詞+助詞"), several categories joined with "+" — neither
-- shape is filterable with a plain equality check. pos_categories holds
-- just the top-level category (or categories) as an array, e.g. {動詞}
-- or {助詞,形容詞}, so filtering is `? = ANY(pos_categories)`.
--
-- Pure SQL over data already in the table — no new Sudachi calls needed,
-- unlike V8/V10 which resolved their source data via a local batch. Verified
-- against real rows before writing this (の -> {助詞}, でもない ->
-- {助詞,形容詞}) — see design.md.
ALTER TABLE word_frequency ADD COLUMN pos_categories TEXT[];

UPDATE word_frequency
SET pos_categories = (
    SELECT array_agg(DISTINCT split_part(segment, ',', 1))
    FROM unnest(string_to_array(part_of_speech, '+')) AS segment
)
WHERE part_of_speech IS NOT NULL;
