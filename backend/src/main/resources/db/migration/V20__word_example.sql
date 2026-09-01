-- design.md §18: one precomputed example sentence per word (Tatoeba, via
-- manythings.org/anki's jpn-eng pairs, CC BY 2.0 FR) -- picked offline as
-- the shortest sentence containing the word, so there's no raw sentence
-- corpus or per-request ranking to keep in this database, just the answer.
CREATE TABLE word_example (
    normalized_form TEXT NOT NULL,
    reading TEXT NOT NULL,
    japanese_text TEXT NOT NULL,
    english_text TEXT NOT NULL,
    PRIMARY KEY (normalized_form, reading)
);
