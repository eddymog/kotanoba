# Kotanoba — Design Document

Companion to `claude.md`. `claude.md` is the stable reference: stack, architecture
rules, domain model, build order. This file is where open decisions get worked
through — options and tradeoffs first, the actual decision recorded once made — plus
a running progress checklist against the build order.

Sections below marked **[discuss]** are ones I'll lay out options for and wait on,
per the working agreement in claude.md. Sections marked **[draft]** I've written a
first pass of; edit freely.

---

## Open decisions

Numbers here match the section numbers below.

| # | Decision | Status |
|---|---|---|
| 1 | Japanese tokenizer library | **Decided — Sudachi** |
| 2 | Schema for Slice 1 (`lemma`, `word_form`, `user_lemma_status`, `text`, `text_token`) | **Decided — V1 migration written** |
| 3 | API contract for Slice 1 | **Decided — locked** |
| 4 | Slice 1 import path — synchronous or through the durable job queue? | **Decided — sync for Slice 1** |
| 5 | Read-path query (load text + merge statuses) | **Decided and implemented** — `TextReadRepository` |
| 6 | `SKIP LOCKED` worker loop and lease handling | **Rejected — out of scope, see #12** |
| 7 | Bitmap caching and invalidation logic | **Rejected — plain SQL instead, see #12** |
| 8 | Frontend component shape | Draft |
| 9 | Frequency-ranked vocabulary reference (top ~8–10k lemmas) | Role + sort metric decided; source and schema placement open — Slice 3 |
| 10 | Persistence strategy — JPA vs. raw SQL, per operation | **Decided** |
| 11 | Toolchain findings from getting Slice 1's backend running | Reference — not a decision |
| 12 | Over-engineering review — job queue and bitmap caching | **Decided — both rejected** |

---

## 1. Tokenizer selection **[decided]**

Because Japanese has no spaces between words, the tokenizer isn't just doing
lemmatization (spaCy's job in the old German design) — it's also doing word
segmentation, which is ambiguous and dictionary-dependent. This is the single
decision that shapes the most downstream work, so worth getting right before Slice 1.

One thing that changes the calculus versus a typical NLP-service choice: per the
hard architectural rule, **Python is never on the read path** — it's called once per
document, asynchronously, from the import worker. Raw tokens/sec throughput matters
much less here than dictionary quality and what the library gives you for free.

### Option A — Sudachi (SudachiPy)

- Actively maintained (Works Applications), pure Python package — no compiled binary
  to manage in the Docker image.
- **Multi-granularity tokenization** (modes A/B/C): the same text can be segmented
  fine (`東京`/`都`/`庁`) or coarse (`東京都庁` as one unit). This is a direct analogue
  of the German compound-splitting problem, except here it's exposed as a
  configuration knob instead of something you have to build.
- Each token comes with a `normalized_form` (collapses kanji/kana script variants —
  solves problem 4 in claude.md for free) and a `reading_form` (katakana reading —
  solves most of problem 2, furigana, for free).
- Tradeoff: heavier dictionary (SudachiDict-full is roughly 1GB), slower cold start.
  Irrelevant on the read path per the architecture rule; only affects import-worker
  startup time.

### Option B — fugashi + UniDic

- Thin, fast Cython wrapper around the MeCab engine. Very mature, widely used
  (it's what most Japanese BERT tokenizers use under the hood).
- UniDic gives lemma, reading, and pronunciation per token.
- Single granularity — no built-in compound-splitting knob like Sudachi. MeCab's
  default segmentation is generally good, but you'd be building problem 1's
  "coarse vs. fine" handling yourself instead of getting it as a mode switch.
- Modern fugashi wheels bundle the MeCab binary, so it's still pip-installable
  without a separate system package — better than older `mecab-python` setups.

### Option C — Janome

- Pure Python, zero system/compiled dependencies at all — simplest possible Docker
  setup.
- Meaningfully slower than the other two, but since the service is only called from
  an async import worker (never the read path), this mostly costs import-job
  latency, not user-facing latency.
- Smaller, less actively maintained dictionary — worth stress-testing against messy
  authentic text (news, forums, light novels) before committing, since dictionary
  coverage on slang/named entities is what actually determines import quality.

### Recommendation

Sudachi. It directly answers two of the four Japanese-specific problems
(script-variant normalization, reading) as output fields rather than things to build,
and its granularity modes give a real lever for the segmentation-ambiguity problem
instead of a fixed MeCab-style tokenization. The Docker story is also simplest of
the three that matter (pure Python, unlike fugashi's bundled binary).

**Decision:** Sudachi.

### Verified against the real library

Spike done — service built and exercised against real text. What held up and what
did not:

| Claim | Result |
|---|---|
| Script variants collapse to one lemma | **Confirmed.** `できる`/`出来る` → 出来る; `がんばる`/`頑張る` → 頑張る; `わたし`/`私` → 私 |
| Inflections resolve to dictionary form | **Confirmed.** 食べます/食べた/食べて/食べられる/食べ始める → 食べる |
| Readings are context-dependent | **Confirmed, but not by the example in claude.md** — see below |
| Granularity modes give a real lever | Partly — see below |

**The `今日は` example does not reproduce.** claude.md cites 今日 reading こんにち in
`今日は`; Sudachi returns キョウ in both plain and greeting contexts. The claim about
Japanese is fine, it just isn't a case Sudachi disambiguates. The example that *does*
demonstrate it, and is now the regression test:

```
学校に行った。  行っ -> イッ      (went)
会議を行った。  行っ -> オコナッ   (held/conducted)
```

Same three characters, two readings, separated only by context. This is the
concrete proof that furigana must come from per-token analysis.

**Mode A is coarser than design.md assumed.** With `sudachidict-core`, `東京都庁`
splits `東京`/`都庁` in modes A *and* B, not `東京`/`都`/`庁`. Mode C keeps it whole.
So the granularity lever exists but has two positions here, not three.
`sudachidict-full` may differ; not worth chasing.

**Chose mode C as the default.** A learner looking up 東京都庁 wants the whole
compound, not three fragments they already know. Overridable per request.

**New finding — `normalized_form` is sometimes an archaic spelling.** `する`
normalizes to `為る`. Fine as a database key, wrong to ever show a user. This is
exactly why `lemma` carries both `normalized_form` (the key) and `dictionary_form`
(the display text) — the split turned out to be load-bearing rather than cosmetic.
Minor consequence: the same lemma can produce different `dictionary_form` values
across texts, so it is first-write-wins.

**Offsets must be UTF-16, not code points.** Python counts code points, Java counts
UTF-16 units. They agree across almost all Japanese but not above the BMP — emoji
(everywhere in blog/forum text) and rare kanji. `猫🐱が好きです。` is 8 characters in
Python and 9 in Java, so every offset after the emoji would be off by one and the
reader would highlight the wrong word. The service converts before returning.

**Performance is a non-issue**, as the architecture predicted: ~1.7 ms to tokenize
a 1,400-character article, ~850k chars/sec, 7 ms dictionary load at startup. Raw
speed was correctly not a selection criterion.

**`sudachipy==0.6.9` fails to build in Docker on Apple Silicon.** No prebuilt
`manylinux`/aarch64 wheel exists for that version, so `pip install` falls back to
building from source, which needs a Rust toolchain the image doesn't have — a clean
build failure, not a runtime bug. `0.6.11` ships an aarch64 wheel; bumped the pin,
confirmed the image now builds and runs (non-root, correct tokens end to end). Worth
remembering as a class of bug: a dependency pin that resolves fine on macOS via a
local venv can still fail in the container if you never actually build the image.

---

## 2. Schema for Slice 1 **[decided]**

Written: `backend/src/main/resources/db/migration/V1__initial_schema.sql`.
Decisions worth being able to defend:

**Script variants collapse via `lemma.normalized_form`.** Sudachi's
`normalized_form` is the unique key, not the raw dictionary spelling — that's what
makes `できる` and `出来る` one lemma. `dictionary_form` is display-only.

**Uniqueness is `(normalized_form, part_of_speech)`, not `normalized_form` alone.**
Homographs that differ only in POS stay distinct lemmas. Known imprecision: same
normalized form *and* POS but different reading (`開く` = ひらく / あく) collapses to
one lemma. Accepted — rare, and for a learner the two senses are close enough.

**Readings are per-token, not per-lemma.** `text_token.reading` carries Sudachi's
contextual reading and is the furigana source; `lemma.reading_form` is just a
default for the dictionary panel. This is the only correct place for it — `今日`
is きょう or こんにち depending on the sentence, so a lemma-level reading would be
wrong half the time.

**`word_form` is a record, not a lookup.** Sudachi resolves surface → lemma at
import with sentence context, and `text_token.lemma_id` is authoritative. Keeps
import a pure insert and sidesteps ambiguous-surface resolution entirely.

**Absence of a `user_lemma_status` row means `NEW`.** Only non-NEW statuses get
written, so the table scales with words actually touched, not with vocabulary size.

**Composite PK `(user_id, lemma_id)` is the read-path index.** Serves
`WHERE user_id = ? AND lemma_id = ANY(?)` — the one query that loads ~700 statuses
for a 2,000-word article.

**`text.lemma_ids` is a plain `BIGINT[]`, not a serialized bitmap.** Durable and
inspectable in Postgres — this is the only representation now (§12, 2026-08-23:
the RoaringBitmap-in-Redis layer this array was originally meant to feed was
rejected as unnecessary at this app's scale; `lemma_ids` is queried directly).

**Non-word tokens get rows too** (`is_word = false`, null `lemma_id`) so the reader
rebuilds the document in order without re-parsing `body`.

**Resolved sub-question — no raw analyzer output stored.** `text_token` holds the
chosen granularity only. Storing enough to re-segment later is speculative
complexity for a mode change that may never happen; re-import is the correct and
cheap answer if it does.

Deferred: refresh-token storage (goes in its own migration when auth is built —
done, `V3__refresh_token.sql`), and a partial index on `status = 'KNOWN'` for the
Slice 3 difficulty query — the PK prefix already covers it, revisit only if it's
ever actually slow.

---

## 3. API contract for Slice 1 **[decided]**

Paste-text → tokenize → store → render → click word → set status → persists on
reload. Locked for Slice 1:

| Endpoint | Purpose |
|---|---|
| `POST /api/texts` | Submit pasted text; tokenizes and returns the created text synchronously (see decision #4) |
| `GET /api/texts/{id}` | Fetch a text's tokens merged with the current user's statuses for the lemmas in it |
| `GET /api/texts` | List library (id, title, created date — difficulty scoring comes in Slice 3) |
| `PUT /api/lemmas/{id}/status` | Set status (`NEW`/`LEARNING`/`KNOWN`/`IGNORED`) for a lemma |

`GET /api/texts/{id}` is the read-path query from decision #5 — both the endpoint
shape and the query behind it are now implemented (`TextReadRepository`).

Note for Slice 2: `POST /api/texts` returning the finished text is a synchronous
contract. When URL import moves onto the job queue it returns a job id instead, so
that endpoint's response shape changes (or URL import gets its own endpoint). Worth
knowing now rather than discovering it mid-slice.

---

## 4. Import pipeline — Slice 1 scope **[decided]**

claude.md specifies a durable, multi-worker, `SKIP LOCKED` job queue for imports,
and Slice 2 explicitly says URL import gets "moved onto" that queue — which reads as
though Slice 1's paste-text path might be a direct synchronous call instead of going
through the queue on day one. Worth deciding explicitly rather than leaving it
implied:

- **Sync for Slice 1:** simpler, ships faster, matches "thin vertical slice first."
  Means the queue infrastructure is net-new work in Slice 2, not a refactor of
  Slice 1's path.
- **Queue from Slice 1:** more consistent with "not `@Async`" language in claude.md,
  avoids touching the import path twice. Means Slice 1 carries more infrastructure
  weight before anything is visibly working.

**Decision: sync for Slice 1.** `POST /api/texts` calls FastAPI inline and returns
the created text. The `SKIP LOCKED` queue is net-new work in Slice 2, not a
refactor of a Slice 1 path.

What this means concretely:

- Resilience4j still wraps the FastAPI call in Slice 1 (timeout, retry, circuit
  breaker) — with no queue to absorb failures, the synchronous path is where a
  Python outage actually surfaces, so the user gets a clean error rather than a
  hung request. This is the one piece of Slice 2's durability that can't wait.
- Keep the tokenize→persist logic in a service the controller calls, not in the
  controller. Slice 2's worker then calls the same service; only the trigger
  changes. That's what keeps this from being a rewrite.
- Accepted tradeoff: a long pasted text blocks its HTTP request. Fine for Slice 1
  (single user, pasted text is short by nature). Slice 2 fixes it properly.

---

## 5. Read-path query **[implemented]**

The query behind `GET /api/texts/{id}`. Opening a 2,000-word article means combining
2,000 token rows with the user's statuses — the rule from claude.md is that this
costs ~700 status lookups (one per distinct lemma), never 2,000.

**Decision: two queries, merged in Java.**

1. Tokens for the text, ordered — served by `text_token`'s PK `(text_id, position)`,
   so it's an ordered range scan with no sort step.
2. Statuses for the distinct lemma ids — `WHERE user_id = ? AND lemma_id = ANY(?)`,
   served by `user_lemma_status`'s PK `(user_id, lemma_id)`.
3. Merge into a `Map<lemmaId, Status>` and walk the token list. Missing key = `NEW`
   (no row means NEW, per §2).

Why not a single `LEFT JOIN`: it works and it's less code, but status would repeat
on every token (~3× redundancy at 2,000 tokens / 700 lemmas), and it welds "what
words are in this text" to "what does this user know" in one query. Keeping them
separate is just cleaner regardless of what Slice 3 ends up doing with difficulty
scoring (§12: turned out to be a plain SQL query too, not a bitmap cache — but the
separation here was worth it on its own terms either way).

**Implementation:** `JdbcTemplate`, not JPA — this is the read path claude.md means
by "know when to bypass the ORM." Hibernate would want entity graphs for a join
that's really just two flat projections.

**Built:** `TextReadRepository` (`backend/src/main/java/com/kotanoba/text/`),
wired into `TextController.get()`. Ownership is checked via
`findByIdAndUserId` before the read happens at all — another user's text 404s,
it doesn't 403, matching login's "don't confirm what exists" reasoning. Verified
live against the real stack: a lemma appearing twice in one text (は, positions 1
and 9) updates at both positions from a single status write, confirming status
really does attach to the lemma and not the surface-form occurrence, all the way
through the read path; punctuation tokens carry `status: null`, not `"NEW"`.

**Benchmark to record in the README** (claude.md asks for numbers): this query at
2,000 tokens, vs. the naive per-token lookup. That contrast is the whole point of
the design. Still outstanding — worth doing once there's a text long enough to
make the numbers meaningful.

---

## 6. `SKIP LOCKED` worker loop and lease handling **[rejected, 2026-08-23]**

Was a placeholder for Slice 2's durable import queue. That queue is now explicitly
out of scope — see the over-engineering review below and claude.md's Out of Scope
section. Nothing to design here unless the durable-queue decision itself gets
revisited, which would need an actual reason (multi-user, or import genuinely
failing mid-request in practice), not a preemptive one.

---

## 7. Bitmap caching and invalidation **[rejected, 2026-08-23]**

Was a placeholder for RoaringBitmap-in-Redis difficulty scoring. Replaced by a
plain SQL query — see the over-engineering review below. No caching layer, so
nothing to design here: the query runs fresh on every request, which is fast
enough at this app's actual scale that a cache would be solving a problem that
doesn't exist yet.

---

## 8. Frontend component shape **[draft]**

Rough breakdown for Slice 1, React + TypeScript + Vite + TanStack Query:

- `TextImportForm` — paste box, submit
- `Reader` — renders a text's tokens; each token is a clickable span colored by
  lemma status
- `StatusPopover` — appears on word click; shows lemma, reading (once tokenizer
  gives one), and status buttons
- `Library` — list of imported texts (difficulty sort comes later)

State: TanStack Query owns text + status server state; status mutations optimistic-
update the local token render so clicking a word recolors instantly rather than
waiting on the round trip.

---

## 9. Frequency-ranked vocabulary reference **[discuss]**

New requirement from you: seed the system with a frequency ranking of the top
~8,000–10,000 most common Japanese lemmas, and use it when a text is opened/listed —
score how much of the text's vocabulary you already know, weighted by frequency
rather than a flat known/total lemma count. Lemmas outside the top 8–10k are still
tracked normally (status stored on read/click); they just sit outside the frequency
reference.

Not drafting the schema or scoring change — per the working agreement, both are
yours. Two things worth deciding, since they lead to different designs:

### 9a. What the frequency list is *for*

- **Bootstrapping only** — a one-time onboarding step: pick a rank cutoff (or a
  level like "N3-ish") and bulk-set `user_lemma_status = KNOWN` for lemmas at or
  above that frequency, as a running start. After that, `user_lemma_status` is
  earned the normal way (reading, clicking, SRS) — the frequency list plays no
  further role.
- **Ongoing scoring signal only** — `user_lemma_status` is never bulk-written by the
  frequency list; instead, "text difficulty" for the library view becomes a
  frequency-weighted score (e.g. sum of inverse-rank, or coverage restricted to the
  top-N band) instead of a flat known-lemma ratio. This changes the Slice 3 scoring
  formula, not how status gets set.
- **Both** — bootstrapping for a fast start, and frequency-weighted scoring
  afterward as the permanent library-sort formula. Most powerful, but two features
  instead of one; worth confirming both are wanted before scoping Slice 3.

**Decided: scoring signal only.** `user_lemma_status` is never bulk-written by the
frequency list — it's earned exclusively by reading/marking words. Slice 1's schema
and status-writing path are unaffected.

**Purpose:** make sure study effort goes to the words that pay off most. A learner's
time is the scarce resource, and a word in the top 2,000 is worth vastly more than
one at rank 40,000 that appears once a year.

Bootstrapping is explicitly not useful here — you start from scratch, so there's
nothing to seed. Possible later, low value.

This purpose implies **two distinct uses**, and they aren't the same thing:

- **Text-level** — how much of this text do I already know, weighted by how common
  the words are. Drives library scoring/sorting (Slice 3).
- **Word-level** — among the words I *don't* know, which should I learn first?
  Ranks unknown lemmas by frequency. Drives the SRS introduction order (Slice 4),
  and possibly emphasis in the reader.

The word-level use is the one that directly serves "learn the most useful words
first," and it was missing from the earlier framing. It matters most in Slice 4:
**FSRS should introduce new cards in frequency order**, not arbitrary order.
Otherwise the SRS happily drills a rank-30,000 word before a rank-800 one.

### 9d. What the library sort optimizes for

**Decided: readability — "what can I read right now."** Each metric does one job:
the library sort answers *what to read next*, and word-level frequency ranking
answers *what to learn next*. Keeping them separate keeps both simple.

```
for each distinct lemma in text:
    weight = 1 / log(rank)      # common words count for more
    if known: knownSum += weight
    allSum += weight

score = knownSum / allSum       # 0.94 = "you know 94% of
                                #         what matters here"
```

Sort descending: most readable first. Frequency weighting is what makes this
better than a flat known/total ratio — missing ten rank-30,000 words barely dents
comprehension, missing one rank-200 word wrecks it.

Rejected: ranking by *learning value* (how many common unknown words a text would
teach). It optimizes vocabulary gain directly, but scores exhausting texts highly —
a passage full of unknown common words has enormous payoff and is miserable to
read. Comprehensible input beats maximal payoff.

Open when Slice 3 starts: whether `1 / log(rank)` is the right weight, and what
happens to lemmas outside the top 8–10k (weight 0, or a small floor). Both are
tuning, cheap to change once there are real texts to test against.

### 9b. Where the frequency data lives

- **Column on `lemma`** (`frequency_rank INT NULL`, null beyond top 10k) — simplest,
  one join-free lookup, but ties the reference data's lifecycle to the `lemma` table
  (re-seeding a frequency source means updating rows in place).
- **Separate reference table** (`lemma_frequency(lemma_id, rank, source)`) — keeps
  the reference data swappable/versionable (different frequency corpora, e.g. BCCWJ
  vs. a subtitle-frequency list) without touching `lemma` rows; costs one more join
  on the scoring path, which (per §7) is a plain SQL query anyway — a well-indexed
  join at this app's scale is not a real cost either way.
- Either way, the frequency source itself needs picking (e.g. BCCWJ, a
  subtitle/Netflix-style frequency list, or a curated JLPT-tier list) and a one-time
  seed migration — the harder open question is whether the source's tokenization
  matches Sudachi's lemma boundaries closely enough to map cleanly, which is worth a
  quick spot-check before committing.

### 9c. Interaction with difficulty scoring (decision #7)

No bitmaps to interact with (§7, rejected) — frequency weighting is just another
column in the same SQL query, joined or referenced alongside `user_lemma_status`,
contributing a `1/log(rank)`-style weight per lemma (§9d) to one score computed in
one query. Nothing extra to design here beyond the query itself, when it gets
written.

**Decision on 9a:** scoring signal only (no bootstrapping). 9b (schema placement:
column vs. reference table) and the frequency source itself are still open — worth
resolving alongside decision #7 when Slice 3 design starts, since this doesn't block
Slice 1.

---

## 10. Persistence strategy — JPA vs. raw SQL **[decided]**

Both, with a deliberate boundary. JPA/Hibernate for ordinary CRUD; SQL for the
paths where the ORM's bookkeeping buys nothing.

### The frequency picture that drives this

Two very different write profiles:

- **Marking words while reading** — the dominant interaction by orders of
  magnitude. Thousands of writes over a reading session, continuously, forever.
- **Importing a text** — occasional. A handful a week.

So the status write is the hot path, and it gets optimized first. Import is a
throughput problem, not a latency one.

### Per-operation split

| Operation | Tool | Why |
|---|---|---|
| Register / log in a user | **JPA** | Plain CRUD, small object, real mutation |
| Create the `text` row on import | **JPA** | One row, one insert |
| List the library (Slice 1) | **JPA** | Small result; moves to SQL in Slice 3 when difficulty sorting lands |
| **Mark a word's status** | **SQL `ON CONFLICT`** | **Hot path — see below** |
| Bulk-insert `text_token`s | JdbcTemplate batch | claude.md mandates; 2,000 rows |
| Upsert `lemma` / `word_form` at import | SQL `ON CONFLICT` | Bulk *and* upsert — Hibernate's weak spot |
| `GET /api/texts/{id}` read path | JdbcTemplate | Decision #5 |

### Why the status write is SQL, not JPA

It looks like textbook CRUD, but it's an **upsert** — no row exists the first time
a word is marked, a row exists every time after. In JPA that's find-then-save: two
round trips, plus a race if two requests land together. One statement instead:

```sql
INSERT INTO user_lemma_status (user_id, lemma_id, status)
VALUES (?, ?, ?)
ON CONFLICT (user_id, lemma_id)
DO UPDATE SET status = EXCLUDED.status, updated_at = now();
```

One round trip, hits `user_lemma_status`'s PK directly, and is atomic — no
read-modify-write window. Given this fires thousands of times, halving its round
trips is the single highest-leverage write optimization in the app.

Note this is *not* a scaling argument — single user, a click is ~1ms either way.
It's that the upsert is simply the correct shape for the operation, and JPA
expresses it badly.

### Deferred: bulk "mark remaining as known"

Not in Slice 1 — per-word clicks only, keeping the slice thin. Revisit after
reading a few real texts, when it's clear how it should behave.

Worth knowing it's nearly free when the time comes, because it reuses
`text.lemma_ids` and the same upsert shape:

```sql
INSERT INTO user_lemma_status (user_id, lemma_id, status)
SELECT ?, unnest(lemma_ids), 'KNOWN' FROM text WHERE id = ?
ON CONFLICT (user_id, lemma_id) DO NOTHING;
```

`DO NOTHING` rather than `DO UPDATE` so it never overwrites a word deliberately
marked `LEARNING`.

Flag to watch while using the app: if clearing a page means clicking every known
word one at a time, that friction is the thing most likely to stop daily use. If
that shows up, promote this to the top of Slice 2.

### Rule

**`text_token` is never mapped as a `@Entity`.** If the entity doesn't exist,
nobody can accidentally write the loop that fires 700 lazy-load queries. The
boundary enforces itself rather than relying on discipline.

### Known friction

`text.lemma_ids` is a `BIGINT[]`; Hibernate needs `@JdbcTypeCode(SqlTypes.ARRAY)`
to map it. Mild, not a blocker.

### First integration test

`backend/src/test/java/com/kotanoba/text/TextImportIntegrationTest.java` — the
first backend test of any kind (there were zero before this). Testcontainers
Postgres + a real NLP container built from `nlp/Dockerfile` (not mocked — Sudachi's
own correctness is already covered by `nlp/tests/`; this test's job is the
backend's own persistence correctness). Both containers are class-level `static`
fields, so they're started once and reused across every `@Test` in the class per
claude.md's "reuse containers."

Caught one real test-design bug before trusting it: an assertion counted the whole
`lemma` table (`SELECT count(*) FROM lemma`), which passed or failed depending on
JUnit's undefined method execution order once a second `@Test` in the same class
started sharing the same Postgres container. Fixed by scoping every count to the
specific text's own `lemma_ids`, which is also the more correct assertion —
verifies referential integrity for *this* import, not global table size.

`mvn clean verify`: 2 tests, 0 failures.

### Slice 3 consequence — superseded, 2026-08-23

This used to say every status write invalidates a cached known-lemma bitmap, and
that the bitmap would need incremental updates rather than a full rebuild per
click. Moot now: §12 rejected the bitmap/cache layer entirely, so there's nothing
to invalidate — the Slice 3 difficulty query just reads current
`user_lemma_status` rows fresh on every request, same as everything else. Worth
keeping this note as a record of *why* that would have been a real problem if the
cache had been built — the write frequency here is genuinely high (decision #10),
so "just recompute the cache" was never going to be a safe shortcut, which is
part of why avoiding the cache altogether was the better call.

---

## 11. Toolchain findings from getting Slice 1's backend running

Not a design decision — a record of real bugs hit wiring the pieces above together
and getting a genuine end-to-end request working, not just a compile. Worth keeping
because none of these were guessable from documentation; all four were found by
actually running the thing against real Postgres and a real Sudachi call.

**`@Lob` on a Postgres `TEXT` column is wrong, not just unnecessary.** Hibernate
maps `@Lob String` to Postgres's `oid` large-object type, not `TEXT`. `text.body`
is defined as plain `TEXT` in the migration (correctly — Postgres `TEXT` has no
size cap, so `@Lob`'s streaming semantics buy nothing), and the mismatch is a real
`SchemaManagementException` at startup, not a style nit.

**JPA's `INSERT` writes every mapped column explicitly — a DB-side `DEFAULT now()`
never fires.** `created_at NOT NULL DEFAULT now()` looks like it should just work;
instead Hibernate sends an explicit `NULL` for any unset field and the `NOT NULL`
constraint fails. Fixed with `@CreationTimestamp` (client-side generation) on all
three entities that have a `created_at` column — `AppUser` and `Lemma` weren't
hitting this yet (neither is currently written through JPA `save()`), but would the
moment something did, so fixed there too rather than left as a landmine.

**`JsonNullable<T>` needs its Jackson module registered, or every response with an
optional field fails to deserialize.** The generated NLP client represents
Pydantic's `Optional[...]` fields as `JsonNullable<T>` (distinguishes "absent" from
"null"). Without `JsonNullableModule` registered on the `ObjectMapper`, Jackson
throws `InvalidDefinitionException` the first time it meets one — which is every
tokenize response, since every per-token field (`normalized_form`, `reading`, ...)
is optional. One `@Bean JsonNullableModule` fixes it; Spring Boot's Jackson
autoconfiguration picks up any `Module` bean automatically.

**Spring Boot 3.4+'s auto-detected `JdkClientHttpRequestFactory` doesn't work
against Uvicorn.** `RestTemplateBuilder.build()` now defaults to
`java.net.http.HttpClient`-backed `JdkClientHttpRequestFactory` rather than the
classic `SimpleClientHttpRequestFactory`. `HttpClient` defaults to attempting
HTTP/2; Uvicorn (h11, HTTP/1.1-only here, no TLS) rejects that negotiation outright
— "400 Bad Request: Invalid HTTP request received," not a normal FastAPI
validation error, which makes it look like a payload bug rather than a protocol
one. An identical raw `curl` request succeeds, which is what isolated it. Fixed by
explicitly requesting `SimpleClientHttpRequestFactory` in `NlpClientConfig` — always
HTTP/1.1, no negotiation to go wrong.

**Unrelated to the app, but worth flagging if `mvn spring-boot:run`/a packaged jar
ever crashes with a `ClassNotFoundException` on a bare, unqualified class name**
(no package prefix) from deep inside reflection: on this machine, the IDE's
background Java compiler (ECJ) and Maven's own build both write to
`backend/target/classes`, and a background recompile can race Maven's `package`
goal and land broken "unresolved compilation" stub classes in the jar between
Maven's compile and its jar step. `javap -v` on the offending class shows the
tell — real javac output never has a bare `LSomeType;` descriptor for a real type,
but ECJ's recovery stubs do, along with a giveaway string constant:
`"Unresolved compilation problems: ..."`. Not a code bug and not fixable from
`pom.xml`; the mitigation used here was rebuilding and checking the packaged jar's
actual bytecode (not just trusting a green Maven build) until a clean one landed.

**`Argon2PasswordEncoder.encode()` throws `NoClassDefFoundError` at call time, not
at startup, without Bouncy Castle on the classpath.** claude.md specifies Argon2;
`spring-security-crypto` implements `Argon2PasswordEncoder` against Bouncy
Castle's API but deliberately doesn't bundle Bouncy Castle itself (licensing/size
— it's a separate, sizeable dependency Spring Security won't impose on projects
that don't use Argon2). The registration endpoint compiled fine and only failed
the first time it actually ran, which is exactly the kind of gap "compiles" can
hide. Added `org.bouncycastle:bcprov-jdk18on` explicitly.

**Without `httpBasic`/`formLogin`, Spring Security has no default
`AuthenticationEntryPoint` — a missing token gets 403, not 401.** 403 means
"authenticated but not allowed"; 401 means "you're not authenticated, try again
with credentials," which is the actually-correct signal for a bare `Authorization`
header being absent. This app disables both `httpBasic` and `formLogin` (JWT is
the only auth mechanism), which also silently drops the entry point that would
normally produce 401. Fixed with an explicit `authenticationEntryPoint` in
`SecurityConfig` that just returns 401.

**A `ResponseStatusException`'s real status can get silently overwritten by 401,
app-wide, once a custom `authenticationEntryPoint` exists.** This one cost real
time to find and is worth understanding, not just fixing. `ResponseStatusException`
is delivered via `HttpServletResponse.sendError()`, which makes the servlet
container internally forward the request to `/error` to render it. That forwarded
request runs back through the *entire* Spring Security filter chain — Spring
Security has no way to know "this is the same request, just rendering its own
error." `/error` was never added to `permitAll()`, so `anyRequest().authenticated()`
denied that second pass, and the entry point's 401 overwrote the controller's real
status. `AuthController`'s 409 on a duplicate email and `TextController`'s 404 on a
missing/foreign text were both silently becoming 401 — every
`ResponseStatusException` in the app, not just one endpoint. Confirmed by tracing a
real request with `logging.level.org.springframework.security=TRACE`: the log
showed `Completed 409 CONFLICT` immediately followed by a second full pass through
the filter chain ending in `"access is denied"`. Fixed by permitting `/error`
explicitly; a regression test now pins the correct status for this specific case
(`TextImportIntegrationTest.responseStatusExceptionsReturnTheirRealStatusNot...`).

---

## 12. Over-engineering review **[decided, 2026-08-23]**

Prompted by pinning down actual scale for the first time: single user, a library
that will realistically reach hundreds of documents, tens of thousands of known
words over time — "that's pretty much it." Two pieces of planned infrastructure
were re-examined against that number, not against what a larger app would need.

**Rejected: the durable `SKIP LOCKED` job queue** (`import_job` table, three
workers, lease handling, exponential backoff, dead-letter). Its justification was
never "the app is too slow" — Slice 1's synchronous import already works — it was
resilience against a crash mid-import, plus the interview value of building it.
For one user on one backend instance, that's a real but rare failure mode, and the
fallback (the import fails, you paste it again) is cheap. The interview-value
argument was named explicitly and ruled insufficient on its own: claude.md already
says this project exists partly to teach interview-relevant skills, so "this would
be good practice" can't be the deciding factor for *every* piece of infrastructure
or the over-engineering guardrail means nothing. See claude.md's Import pipeline
and Out of Scope sections, and decision #6 above.

**Rejected: RoaringBitmap difficulty scoring cached in Redis.** Replaced by a plain
SQL query, computed fresh on every request, no caching layer:

```sql
SELECT text.id,
       count(*) FILTER (WHERE uls.status = 'KNOWN') AS known_count,
       array_length(text.lemma_ids, 1) AS total_count
FROM text
CROSS JOIN LATERAL unnest(text.lemma_ids) AS lemma_id
LEFT JOIN user_lemma_status uls
       ON uls.lemma_id = lemma_id AND uls.user_id = text.user_id
WHERE text.user_id = ?
GROUP BY text.id
```

Already served by an index that exists for an unrelated reason —
`user_lemma_status`'s primary key is exactly `(user_id, lemma_id)` (decision #2).
At "hundreds of documents, tens of thousands of known words," this is single-digit
milliseconds, no bitmap library, and — concretely — Redis isn't running anywhere in
this stack right now, so skipping it avoids standing up new infrastructure *and*
solving cache invalidation, which decision #10 already flagged as the hard part of
the bitmap design if it were ever built. Frequency weighting (§9) survives this
change unchanged — it's just another term in the same query, not something that
depended on bitmaps existing. See claude.md's Core domain model section, and
decisions #6 and #7 above.

**What this doesn't touch:** the read-path query (decision #5) stays exactly as
built — merging 2,000 tokens against ~700 statuses in one query instead of 700 is
not scale-dependent the way library-wide difficulty scoring is; it's the
difference between one query and 700 on a single page load, which matters at any
scale including one user. Resilience4j on the NLP call also stays — cheap (a few
annotations, no new service to run) and defends against a failure mode this
deployment genuinely has (Render's free tier can restart or cold-start the NLP
service). The distinction that mattered throughout: infrastructure whose cost is
"more code in this codebase" survived; infrastructure whose cost is "a new service
to operate, with its own failure modes and invalidation logic" did not, unless the
app actually needs it at its actual scale.

---

## Progress checklist

Mirrors the build order in claude.md. Check off as work lands and is deployed —
per claude.md, each slice ships before the next starts.

### Slice 1 — thin vertical slice (target: live and deployed)
- [x] Decide tokenizer (decision #1) — Sudachi
- [x] Decide schema (decision #2)
- [x] Flyway migration for `lemma`, `word_form`, `user_lemma_status`, `text`, `text_token`
- [x] Lock API contract (decision #3)
- [x] Decide sync-vs-queue for Slice 1 import (decision #4) — sync
- [x] Decide read-path query (decision #5) — two queries, merge in Java
- [x] FastAPI tokenizer endpoint (pure function, no DB) — `nlp/`, 24 tests green
- [x] Java client generated from FastAPI's OpenAPI schema — `backend/`, openapi-generator-maven-plugin off the checked-in schema
- [x] Resilience4j wrapping (timeout, retry, circuit breaker) on the FastAPI call — `NlpTokenizerClient`
- [x] Paste-text import endpoint — `POST /api/texts`, verified with a real Sudachi call end to end
- [x] JDBC batch token persistence (not `saveAll()`) — `TextTokenBatchWriter`; lemma/word_form bulk-upserted via `unnest` too
- [x] Read-path query: text + merged statuses — `TextReadRepository` (two queries, merged in Java, per decision #5); `GET /api/texts/{id}` now returns the real merged token+status list, ownership-scoped (404, not 403, for another user's text). Verified live: repeated occurrences of a lemma (は at two positions) update together from one status write; punctuation tokens carry no status; an untouched lemma reads NEW.
- [x] Reader UI renders tokens, click sets status — `frontend/` (React + TS + Vite + TanStack Query). Built and code-reviewed with no browser available in that session (caught and fixed two real bugs that way: a click-bubbling issue that would have kept the status picker from ever opening, and picker state keyed by lemma id instead of token position, which would have popped a picker open under every occurrence of a repeated word at once). Confirmed working in an actual browser afterward.
- [x] Status persists across reload — confirmed in-browser: set a status, reload, it's still there.
- [x] Real auth (Spring Security, JWT + refresh, Argon2) — `com.kotanoba.user`: `POST /api/auth/{register,login,refresh,logout}`. Access tokens are short-lived, self-signed JWTs (HS256, no DB lookup to verify); refresh tokens are opaque random strings, hashed at rest (`V3__refresh_token.sql`), rotated on every use, revocable. `CurrentUser` now reads the authenticated principal instead of its old hardcode. Verified live against the real Compose stack: unauthenticated requests get 401, wrong password gets a generic 401 (no email-enumeration signal), a stolen/reused refresh token is rejected after rotation, and two different registered users each see only their own texts.
- [x] Docker Compose brings up the full stack with one command — `compose.yaml` at project root (postgres + nlp + backend, healthcheck-gated startup order). Verified live: `docker compose ps` shows all three healthy/up, and a smoke-test import (`POST /api/texts`, id 1) round-tripped through all three containers.
- [x] CI green — repo pushed to `github.com/eddymog/kotanoba`; both jobs (backend `mvn verify` including the real Testcontainers integration test, NLP pytest) pass on GitHub's own infrastructure, not just locally. First run flagged three action-version deprecation warnings (non-fatal); bumped `checkout`/`setup-java`/`setup-python` to current majors, second run is fully clean.
- [x] Deployed to a real URL — free tier, chosen for the actual usage pattern (single user, ~20 min/day): **Neon** (Postgres, scale-to-zero, no forced expiry — unlike Render's own free Postgres, which deletes itself after 30+14 days), **Render** (backend + NLP, both free web services), **Vercel** (frontend static build). Frontend live at `https://kotanoba.vercel.app` (claimed as a free `.vercel.app`
  alias via `vercel alias set` — no domain purchase needed; the auto-generated
  `frontend-eight-indol-91.vercel.app` still works too, just uglier). Vercel's
  Deployment Protection defaulted to gating this alias behind Vercel's own SSO
  login even though the raw deployment URL wasn't gated — disabled for production
  in Project Settings → Deployment Protection, since the whole point is public
  reachability. `FRONTEND_ORIGINS` on the backend updated to match, plus
  `SecurityConfig` now strips whitespace from configured origins — a pasted env
  var value with invisible trailing whitespace is an easy, silent way to break
  exact-match CORS comparisons.

  NLP is a public Render service, not network-isolated — free-tier web services can only send private-network traffic, not receive it, so isolation wasn't actually available at this tier. Substituted a shared-secret header (`X-Internal-Api-Key`) instead; verified live that a request without it gets 401 and one with it succeeds.

  Verified end to end against the real deployed infrastructure, not just locally: register → import (backend calling the real deployed NLP service) → read-path merge → set status → all round-tripped correctly through Neon. CORS confirmed properly scoped — the real Vercel origin gets `access-control-allow-origin` back, an arbitrary other origin does not.

  Both CLIs used for setup (`render`, and briefly `doctl` earlier for the abandoned DigitalOcean path) proved too unstable for non-interactive use in this environment — several commands crashed outright without a real TTY. `neonctl` and `vercel` worked fine non-interactively. Where a CLI failed, config was set directly in that platform's dashboard instead.

**Verified working end to end** (real Postgres in Docker, real Sudachi call, packaged jar — not just "compiles"):
`POST /api/texts` with actual Japanese text → 201 with real token/lemma counts → `GET /api/texts` lists it → `PUT /api/lemmas/{id}/status` upserts (confirmed one row, overwritten not duplicated, on a second call) → `GET /api/texts/{id}` correctly returns 501, not a crash.

### Slice 2 — URL import + durable job queue **[shelved, 2026-08-23]**

**Decision: not building this.** claude.md bundles two separable things under
Slice 2 — URL import (paste a URL, extract readable text, tokenize) and the
durable `SKIP LOCKED` job queue with three workers. Explicitly rejected the job
queue: for a single user on Render's free tier running one instance, the
queue's actual justification is the interview/learning value claude.md names
at the top of the document, not a functional need — and that's not reason
enough on its own to add the infrastructure. Per claude.md's own working
agreement ("push back when I am over-engineering... recognizing when *not* to
add infrastructure is the better signal"), this is exactly that call, made
deliberately, not by default. URL import itself also shelved for now — not
rejected outright, just not currently wanted; if it comes back, it can reuse
Slice 1's synchronous import path directly with no queue involved.

- [ ] ~~`import_job` table (status, attempt count, lease expiry)~~ — shelved
- [ ] ~~`SKIP LOCKED` worker loop (decision #6)~~ — shelved
- [ ] ~~Three worker instances in Compose~~ — shelved
- [ ] ~~Exponential backoff + dead-letter~~ — shelved
- [ ] ~~Frontend polls job status, shows real progress~~ — shelved
- [ ] Readability extraction from URL — not rejected, just not currently wanted; revisit as a plain synchronous addition to Slice 1's import path if it comes back
- [ ] ~~Concurrency test: N threads racing for one job, exactly-once processing~~ — shelved
- [ ] ~~Deployed~~ — shelved

### Slice 3 — difficulty scoring **[revised, 2026-08-23 — no bitmaps/Redis]**
- [ ] ~~Bitmap caching design (decision #7)~~ — rejected, see over-engineering review
- [ ] Frequency reference: pick source, schema placement (decision #9b)
- [ ] Seed migration for top ~8–10k frequency-ranked lemmas
- [ ] Plain SQL difficulty-scoring query (replaces RoaringBitmap intersection)
- [ ] ~~Redis caching of user + text bitmaps~~ — rejected, no cache layer at all
- [ ] Library view sorted by frequency-weighted difficulty
- [ ] Deployed

### Slice 4 — SRS review mode
- [ ] FSRS implementation (not SM-2)
- [ ] New-card introduction ordered by frequency rank (decision #9)
- [ ] Review queue UI
- [ ] Deployed

### Slice 5 — reading disambiguation & script-variant normalization
- [ ] Furigana display from tokenizer reading output
- [ ] Script-variant lemma merge (`できる`/`出来る`)
- [ ] Deployed

### Slice 6 — audio-text alignment
- [ ] WhisperX integration
- [ ] Sentence-level click-to-replay
- [ ] Deployed

---