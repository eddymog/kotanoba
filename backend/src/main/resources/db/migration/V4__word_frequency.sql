-- Kotanoba — Slice 3 frequency reference.
--
-- Design notes live in design.md §9b. Key decisions encoded here:
--   * Source is the jpdb v2.2 frequency list, top 10,000 ranks, spot-checked
--     against Sudachi's dictionary_form() output (16/17 exact matches).
--   * Deliberately NOT a column on `lemma` and NOT keyed by lemma_id. `lemma`
--     rows are only created as a side effect of importing a text (§2), so a
--     lemma_id FK would force bulk-creating ~9,944 placeholder lemma rows for
--     words nobody has read yet. This table is a standalone "how common is
--     this word" reference, seeded independently of any user's reading
--     history, and joined by text at query time.
--   * (term, reading) as the key, not term alone: 149 of the top 10k terms
--     are homographs with distinct readings and distinct ranks (e.g. 上 as
--     うえ vs じょう) — term alone would collide two different words.


-- ---------------------------------------------------------------------------
-- word_frequency — standalone frequency reference, not tied to any lemma row.
--
-- Slice 3's difficulty query joins this to lemma by matching text:
--   lemma.dictionary_form = word_frequency.term
--   AND lemma.reading_form = word_frequency.reading
-- lemma.reading_form is nullable, so a lemma with no representative reading
-- simply won't match — falls through to Slice 3's unmatched-lemma floor
-- weight (§9d), same as any word outside the top 10k.
--
-- Single source, replace-only: (term, reading) is the whole PK, so this
-- table holds exactly one frequency source at a time. Re-seeding from a
-- different source means clearing the table and reloading, not layering a
-- second source alongside the first — `source` is bookkeeping (which list is
-- currently loaded), not a coexistence key.
--
-- Known limitation, not fixed here: term is the surface spelling as jpdb
-- counted it, not lemma.normalized_form, so script variants keep separate
-- ranks (できる rank 94, 出来る rank 1302) until Slice 5's normalization
-- lands. Flagged in §9b; revisit whether to join on normalized_form instead
-- once that's decided.
-- ---------------------------------------------------------------------------
CREATE TABLE word_frequency (
    term    TEXT NOT NULL,
    reading TEXT NOT NULL,
    rank    INT  NOT NULL,

    -- Which frequency list is currently loaded. Bookkeeping only — see the
    -- replace-only note above, this is not part of how rows are looked up.
    source  TEXT NOT NULL DEFAULT 'jpdb_v2.2',

    PRIMARY KEY (term, reading)
);

-- Difficulty scoring reads word_frequency.rank; nothing else queries this
-- table by any other shape, so the PK is the only index needed.
