-- Kotanoba — Slice 1 schema.
--
-- Design notes live in design.md §2. Key decisions encoded here:
--   * Status attaches to `lemma`, never to a surface form.
--   * Sudachi resolves surface → lemma at import; `text_token.lemma_id` is the
--     truth. `word_form` records observed pairs, it is not on the resolve path.
--   * Contextual reading (furigana) lives on `text_token`, not on `lemma` —
--     readings are per-occurrence (今日 = きょう / こんにち).


-- ---------------------------------------------------------------------------
-- app_user
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         TEXT        NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,          -- Argon2
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- ---------------------------------------------------------------------------
-- lemma — the dictionary form. Everything a user "knows" hangs off this row.
-- ---------------------------------------------------------------------------
CREATE TABLE lemma (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Sudachi normalized_form. This is what collapses script variants:
    -- できる and 出来る both normalize here, so marking one known covers both.
    normalized_form TEXT        NOT NULL,

    -- The spelling shown in the UI. Usually the kanji form even when the text
    -- used kana, so the dictionary panel reads naturally.
    dictionary_form TEXT        NOT NULL,

    -- A representative reading for the dictionary panel's default display.
    -- NOT the source of furigana — see text_token.reading.
    reading_form    TEXT,

    part_of_speech  TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- POS is part of the key so homographs stay distinct lemmas: 行った is
    -- 行く (verb) or 行う (verb) — different normalized_form, fine — but the
    -- noun/verb collisions are real and cheap to separate here.
    CONSTRAINT lemma_normalized_pos_key UNIQUE (normalized_form, part_of_speech)
);


-- ---------------------------------------------------------------------------
-- word_form — observed surface → lemma pairs.
--
-- Populated as a side effect of import. Useful for "which forms of 食べる have
-- I actually seen?" and for surface-text search. Deliberately NOT consulted
-- when resolving a token: Sudachi already did that with sentence context.
-- ---------------------------------------------------------------------------
CREATE TABLE word_form (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    surface_form TEXT   NOT NULL,
    lemma_id     BIGINT NOT NULL REFERENCES lemma (id) ON DELETE CASCADE,

    CONSTRAINT word_form_surface_lemma_key UNIQUE (surface_form, lemma_id)
);

CREATE INDEX word_form_lemma_idx ON word_form (lemma_id);


-- ---------------------------------------------------------------------------
-- user_lemma_status
--
-- Composite PK (user_id, lemma_id) is the read path's index: the reader loads
-- ~700 distinct lemmas for a 2k-word article with a single
--   WHERE user_id = ? AND lemma_id = ANY(?)
-- and merges against the token list in memory.
--
-- Absence of a row means NEW. Only non-NEW statuses are written, so the table
-- stays proportional to what has actually been touched.
-- ---------------------------------------------------------------------------
CREATE TABLE user_lemma_status (
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    lemma_id   BIGINT      NOT NULL REFERENCES lemma (id) ON DELETE CASCADE,
    status     VARCHAR(16) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (user_id, lemma_id),
    CONSTRAINT user_lemma_status_status_check
        CHECK (status IN ('NEW', 'LEARNING', 'KNOWN', 'IGNORED'))
);


-- ---------------------------------------------------------------------------
-- text — an imported document.
--
-- `text` is a non-reserved keyword in Postgres, so it works unquoted, but
-- expect to spell it out explicitly in jOOQ/Hibernate mappings.
-- ---------------------------------------------------------------------------
CREATE TABLE text (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    title       TEXT        NOT NULL,
    body        TEXT        NOT NULL,   -- original text; token offsets index into this
    source_url  TEXT,                   -- null for pasted text (Slice 1)

    -- Distinct lemma set for this text, as a plain array. The RoaringBitmap
    -- used for difficulty scoring (Slice 3) is derived from this and cached in
    -- Redis — this column is the durable source, Redis holds the fast copy.
    lemma_ids   BIGINT[]    NOT NULL DEFAULT '{}',

    token_count INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX text_user_created_idx ON text (user_id, created_at DESC);


-- ---------------------------------------------------------------------------
-- text_token — ordered tokens, written once at import via JDBC batch.
--
-- PK (text_id, position) doubles as the ordered-read index, so loading a text
-- is a single ordered range scan with no extra sort.
-- ---------------------------------------------------------------------------
CREATE TABLE text_token (
    text_id      BIGINT  NOT NULL REFERENCES text (id) ON DELETE CASCADE,
    position     INT     NOT NULL,

    -- Half-open [char_start, char_end) offsets into text.body.
    char_start   INT     NOT NULL,
    char_end     INT     NOT NULL,

    surface_text TEXT    NOT NULL,

    -- Contextual reading from Sudachi's analysis of THIS sentence. This is the
    -- furigana source — a static kanji→reading table cannot get 今日 right.
    reading      TEXT,

    -- Null for punctuation, whitespace, symbols — anything not a vocabulary
    -- item. Those tokens still get rows so the reader can rebuild the document
    -- in order without re-parsing body.
    lemma_id     BIGINT  REFERENCES lemma (id),
    is_word      BOOLEAN NOT NULL DEFAULT TRUE,

    PRIMARY KEY (text_id, position)
);

CREATE INDEX text_token_lemma_idx ON text_token (lemma_id);
