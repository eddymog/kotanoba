# Project: Kotanoba — Japanese Reading Trainer

A LingQ-style reading platform for learning Japanese. Import authentic Japanese texts,
read them with per-word status tracking, and review vocabulary with spaced repetition.

This is a portfolio project **and** a tool I will use daily to learn Japanese. Both goals
matter. Decisions that make it a better learning tool usually also make it a better
portfolio piece, because they produce real problems instead of hypothetical ones.

---

## Stack

| Layer | Choice | Notes |
|---|---|---|
| Backend | Spring Boot 3.x, Java 21 | Owns all state, auth, persistence |
| Database | PostgreSQL 16 | Flyway migrations |
| NLP service | FastAPI + Japanese tokenizer (candidate TBD — see design.md) | Stateless, no DB |
| Frontend | React + TypeScript + Vite | TanStack Query for server state |
| Cache | Redis | Bitmaps, session-adjacent data |
| Local dev | Docker Compose | One `docker compose up` starts everything |

### Hard architectural rule

**The Python service is a pure function. It owns no data, has no database connection,
has no concept of a user, and is never on the read path.**

Text in → tokens, lemmas, POS tags out. It is called only from the async import worker.
If Python is down, imports queue and retry; nothing user-facing breaks. If you ever find
yourself wanting to give FastAPI a database connection, stop — that is the design
drifting into a distributed monolith.

React talks only to Spring Boot. Never directly to FastAPI.

---

## Core domain model

The central concept: a user has a **status** for every *lemma* (dictionary form) they
have encountered — `NEW`, `LEARNING`, `KNOWN`, `IGNORED`. Statuses attach to lemmas, not
to surface word forms, so marking `食べる` (taberu, dictionary form) as known also covers
`食べます`, `食べた`, `食べて`, `食べられる`, etc. Lemmas also need to absorb script
variants of the same word — `できる` and `出来る` are the same lemma written differently.

Rough shape (I will write the actual DDL myself — see Working Agreement):

- `lemma` — canonical dictionary form
- `word_form` — surface form → lemma
- `user_lemma_status` — (user_id, lemma_id, status); composite PK
- `text` — imported document, plus its **distinct lemma set** stored on the row
- `text_token` — ordered tokens with char offsets, written once at import

### The performance constraint that shapes everything

Opening a 2,000-word article must not issue 2,000 status lookups. That article contains
maybe 700 distinct lemmas. Fetch those 700 statuses in one indexed query and merge
against the token list in memory.

Text difficulty (what fraction of a text's lemmas the user already knows) is computed as
a **RoaringBitmap intersection** between the user's known-lemma bitmap and the text's
lemma bitmap, both cached in Redis. This must be fast enough to score an entire library
on every page load.

---

## Japanese-specific problems (these are the interesting part)

1. **Word segmentation.** Japanese text has no spaces between words, so tokenization
   itself — not just lemmatization — is the first hard problem. Boundaries are
   ambiguous and dictionary-dependent (`東京都庁` could be one unit or split into
   `東京`/`都庁`/`東京`/`都`/`庁`). This needs a morphological analyzer, not a naive
   splitter. See design.md for the tokenizer tradeoffs (Sudachi vs. fugashi/UniDic vs.
   Janome).
2. **Reading disambiguation (furigana).** Kanji frequently have multiple readings that
   depend on context — `今日` reads `きょう` in most sentences but `こんにち` in fixed
   expressions like `今日は`; `生` alone has roughly eight common readings. The reading
   shown in the dictionary panel must come from the tokenizer's per-token analysis of
   the actual sentence, not a static kanji-to-reading table.
3. **Conjugation to dictionary form.** Verbs and adjectives inflect
   (`食べる` → `食べます`/`食べた`/`食べて`), and authentic text adds grammaticalized
   auxiliary chains on top (`食べ始める`, `食べてしまう`). Resolving a surface form back
   to its lemma needs morphological analysis, not suffix stripping.
4. **Script-variant normalization.** The same lemma is often written in kanji or in
   pure kana depending on register and author choice (`出来る` vs. `できる`, `頑張る` vs.
   `がんばる`). Without normalizing these to one lemma, a user could mark one spelling
   known and still see the other flagged as new.

Do not build multi-language abstractions. Japanese only. Abstracting for languages I do
not have is speculative complexity.

---

## Import pipeline

Durable, resumable, multi-worker. **Not `@Async`** — that loses work on restart.

- `import_job` table with status, attempt count, lease expiry
- Workers poll with `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1`
- Run **three worker instances** in Compose so the locking is real, not theoretical
- Exponential backoff on failure, dead-letter after N attempts
- Frontend polls job status; the UI shows real progress

Flow: URL or pasted text → readability extraction → POST whole document to FastAPI →
persist tokens via JDBC batch (not `saveAll()`) → compute and cache the lemma bitmap.

Use Resilience4j on the FastAPI call: timeout, retry with backoff, circuit breaker.
Generate the Java client from FastAPI's OpenAPI schema so contract drift is a compile error.

---

## Build order — thin vertical slice first

Do not build layer by layer. Get one complete path working end to end, deployed, then widen.

**Slice 1 (target: live and deployed).** Paste Japanese text in a box → tokenize via
FastAPI → store → render in a reader → click a word → set status → status persists and
shows on reload. Real auth. Deployed to a real URL. Docker Compose + CI green.

**Slice 2.** URL import, moved onto the SKIP LOCKED job queue with three workers.

**Slice 3.** Difficulty scoring with bitmaps, library view sorted by difficulty.

**Slice 4.** SRS review mode. Implement **FSRS**, not SM-2.

**Slice 5.** Reading disambiguation (furigana) and script-variant lemma normalization.

**Slice 6.** Audio–text alignment (WhisperX), sentence-level click-to-replay.

Ship each slice deployed before starting the next.

---

## Engineering standards

- **Flyway** for every schema change. No `ddl-auto` beyond `validate`.
- **Testcontainers** for integration tests against real Postgres. Reuse containers.
- Include a **concurrency test**: N threads competing for the same import job, asserting
  exactly-once processing.
- **JPA for ordinary CRUD; drop to jOOQ or JdbcTemplate on the read path.** Knowing when
  to bypass the ORM is the point — do not fight Hibernate on the token join.
- **Spring Modulith** for enforced module boundaries. Modular monolith, no microservice theater.
- Spring Security with JWT + refresh tokens. Argon2 password hashing.
- Correlation IDs propagated into the FastAPI call so logs stitch across both runtimes.
- Benchmark before optimizing, and record the numbers in the README — naive `saveAll()`
  vs. JDBC batch is a good first entry.

---

## Working agreement with the AI assistant

This project exists partly to teach me things I will be asked about in interviews. Code I
accepted without understanding will not survive follow-up questions.

**Write freely:** DTOs, config, boilerplate CRUD, test scaffolding, React components,
Docker and CI setup, Flyway migration file plumbing.

**Do not write for me unless I explicitly ask — explain the tradeoffs and let me write it:**
- the schema and its indexes
- the read-path query that loads a text with statuses
- the `SKIP LOCKED` worker loop and lease handling
- transaction boundaries and isolation choices
- the bitmap caching and invalidation logic

For those, respond with the options and their tradeoffs, then wait. If I ask you to just
write one, ask once whether I want to attempt it first.

**General:** prefer the boring solution. Push back when I am over-engineering — this is a
single-user app for a long time, and recognizing when *not* to add infrastructure is the
better signal. Tell me when I am gold-plating instead of shipping the slice.

---

## Out of scope

Multi-language support. Social features. Mobile apps. Kubernetes. Microservices beyond
the single NLP sidecar. A custom auth provider.