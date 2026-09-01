-- design.md §18: definitions decoupled from word_frequency's rank-only job.
-- word_frequency.meaning only ever covered the top 10k jpdb terms and kept
-- one collapsed gloss per word (§13); this table covers any word JMdict
-- knows about (far more than 10k) and keeps every sense. Keyed the same way
-- as word_frequency's post-dedup key (§16) so every existing lemma-joining
-- query gets it with one more LEFT JOIN, no new matching logic.
CREATE TABLE dictionary_entry (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    normalized_form TEXT NOT NULL,
    reading TEXT NOT NULL,
    senses TEXT[] NOT NULL
);

CREATE UNIQUE INDEX dictionary_entry_normalized_reading_idx
    ON dictionary_entry (normalized_form, reading);
