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
| 9 | Frequency-ranked vocabulary reference (top ~8–10k lemmas) | **Decided — jpdb v2.2 top 10k, `word_frequency` reference table** |
| 10 | Persistence strategy — JPA vs. raw SQL, per operation | **Decided** |
| 11 | Toolchain findings from getting Slice 1's backend running | Reference — not a decision |
| 12 | Over-engineering review — job queue and bitmap caching | **Decided — both rejected** |
| 13 | Vocabulary browse page — interactive triage of the frequency list | **Decided and implemented** — also fixed a real hiragana/katakana bug in difficulty scoring |

---

## 1. Tokenizer selection **[decided]**

Because Japanese has no spaces between words, the tokenizer isn't just doing
lemmatization (spaCy's job in the old German design) — it's also doing word
segmentation, which is ambiguous and dictionary-dependent. This is the single
decision that shapes the most downstream work, so worth getting right before Slice 1.

One thing that changes the calculus versus a typical NLP-service choice: per the
hard architectural rule, **Python is never on the read path** — it's called once per
document, synchronously, from the import request. Raw tokens/sec throughput matters
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
| `GET /api/texts` | List library (id, title, created date — difficulty scoring comes in Slice 3, landed 2026-08-23) |
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

**Decided: source is the jpdb v2.2 frequency list, top 10,000 ranks, 2026-08-23.**

[Kuuuube/yomitan-dictionaries](https://github.com/Kuuuube/yomitan-dictionaries) publishes
a free, no-registration `term / reading / frequency / kana_frequency` TSV scraped from
jpdb.io's corpus of light novels, visual novels, anime, and drama — content much closer
to what this app's users will actually import than BCCWJ's news/blog-heavy register.
Considered and rejected: BCCWJ (more academic/authoritative, but distributed as
short-unit-word counts requiring an access request and unit-conversion work before it's
usable) and the Leeds internet corpus (older, noisier, no reading field).

**Spot-check, before committing:** tokenized a sentence exercising every hard case
claude.md calls out (conjugation, a compound noun, an auxiliary chain, a script variant —
今日は東京都庁に行って、大きい寿司を食べました。友達に頑張ってと言われたので、出来る
ことをやってみます。) with the local Sudachi install and looked up each
`dictionary_form()` against the CSV's `term` column. 16 of 17 lemmas matched by exact
string, with sane ranks (function words rank <20, content words in the hundreds–
thousands). The one miss, `れる` (passive/potential auxiliary), is closed-class grammar,
not vocabulary — covered by a small fallback weight for unmatched lemmas (§9d already
leaves this open as a tuning question).

**Real finding, not just a clean bill of health:** `できる` (rank 94) and `出来る`
(rank 1302) are two separate rows. This is exactly the script-variant problem from
claude.md item 4 — until Slice 5's normalization lands, a text's difficulty score will
depend on which spelling it happens to use, even though a reader who knows one
typically knows the other. Not a blocker, just a known Slice 3 → Slice 5 sequencing
wrinkle.

**Data prep done:** downloaded the full list (278,947 rows), sliced to rank ≤ 10,000
(10,000 rows — filtered on the rank column, not line count, since the file has a small
number of non-sequential rank gaps), then deduped 53 exact `(term, reading)` collisions
(the scrape occasionally counts the same surface form twice, e.g. under different parts
of speech) by keeping the lowest rank per pair. Final artifact: 9,944 unique
`(term, reading, rank)` rows, ranks 1–10,000, sitting in scratch pending the schema
decision below.

**Decided: separate reference table, 2026-08-23.** `word_frequency(term, reading,
rank, source)`, keyed on the *text* of the term/reading rather than a `lemma_id` FK,
replace-only (one source loaded at a time, no coexistence) —
seeds independently of whether any of those 9,944 words have ever been imported yet
(`lemma` rows are only created as a side effect of importing a text, per decision #2,
so a `lemma_id` FK would force bulk-creating placeholder `lemma` rows just to hold a
rank). The Slice 3 query joins by matching `lemma.dictionary_form`/`reading` text
against this table instead. Costs one more join on the scoring path, which (per §7) is
a plain SQL query anyway — not a real cost at this scale. Also keeps the reference
data swappable/versionable (re-seeding from a new source is a table swap, not an
update-in-place on `lemma`) if the source ever changes.

The actual DDL and the seed migration that loads
`backend/src/main/resources/seed-data/jpdb_v2.2_freq_top10k.tsv` are still Eddy's to
write, per the working agreement.

### 9c. Interaction with difficulty scoring (decision #7)

No bitmaps to interact with (§7, rejected) — frequency weighting is just another
column in the same SQL query, joined or referenced alongside `user_lemma_status`,
contributing a `1/log(rank)`-style weight per lemma (§9d) to one score computed in
one query. Nothing extra to design here beyond the query itself, when it gets
written.

**Decision on 9a:** scoring signal only (no bootstrapping). **Decision on 9b:** source
is jpdb v2.2, top 10,000 ranks, spot-checked against Sudachi; placement is a separate
`word_frequency` reference table, keyed by term/reading text (see above, 2026-08-23).
The DDL and seed migration itself are still Eddy's to write, per the working agreement.

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

## 13. Vocabulary browse page **[decided, implemented, 2026-08-23]**

Not in claude.md's original slice list — came out of wanting a page to triage the
frequency list itself: browse the top 10k in bands of 100, see current status per
word, and mark words known directly from there, not only by reading them.

**Decided: interactive, not read-only.** A word_frequency word can be marked before
it's ever been read (word_frequency is deliberately independent of `lemma` — §9b), so
clicking a NEW word here needs to create a `lemma` row on the spot. Rejected a
placeholder part-of-speech for that row (`normalized_form`/`dictionary_form` would
just be the raw term, POS some sentinel like `'UNKNOWN'`) in favor of resolving it
through a real Sudachi call — `NlpTokenizerClient` was already there from Slice 1, and
reusing `LemmaBulkUpsertRepository`'s existing `ON CONFLICT (normalized_form,
part_of_speech)` upsert means a word that normalizes to a script variant already in
the database (marking 出来る when できる was already imported, say) correctly resolves
to that same lemma row instead of creating a duplicate. Falls back to a raw-term
placeholder only if Sudachi can't cleanly resolve the single word to one token — rare,
given the frequency list is already dictionary-form text (§9b's spot-check).

New endpoints: `GET /api/vocabulary?page=N` (rank band `[(N-1)*100+1, N*100]`,
`VocabularyBrowseRepository`), `PUT /api/vocabulary/status` (body: term, reading,
status — keyed by text, not a lemma id, since one may not exist yet;
`VocabularyLemmaResolver` + the existing `UserLemmaStatusRepository` upsert).

**Real bug found and fixed while building this — V6 migration.** `word_frequency.reading`
is hiragana (jpdb's source data, V5's seed); `lemma.reading_form` is always Sudachi's
katakana. Every query joining on both (`wf.reading = l.reading_form`) — this page's
browse query *and* §9's difficulty-scoring query in `TextLibraryRepository` — silently
never matched on reading, so every lemma fell through to the unmatched-word floor
weight regardless of its real rank. Difficulty scoring quietly degraded to a flat
known/total ratio without erroring, which is exactly why the existing integration test
didn't catch it: "mark everything known" vs. "mark nothing known" scores 1.0 vs. 0.0
either way, whether real per-word weighting is active or not. Caught by actually
exercising the write-then-read path end to end against a running instance, not by
reading the code.

Fixed with `V6__word_frequency_reading_katakana.sql` — `translate()` over the full
hiragana block (U+3041–3096) to its katakana counterpart (U+30A1–30F6), a constant
+0x60 codepoint offset verified against real Sudachi output before writing it. New
migration, not an edit to V5, since V5 had already run against real data (same
precedent as V1's checksum fix earlier this session). Added
`difficultyScoreWeighsByRealFrequencyRankNotJustKnownCount` as a regression test —
marking a rank-11 word known vs. marking an unranked word known, same "1 of N known"
shape, asserts the scores differ by a wide margin. Only real per-word weighting can
pass that; the old bug's flat-ratio behavior would have scored both identically.

### Romaji and part of speech, added to the browse page — 2026-08-23

**Romaji:** pure frontend, no decision needed — `katakanaToRomaji.ts`, a lookup-table
converter (gojuon, dakuten/handakuten, youon, common loanword digraphs, sokuon, long
vowel mark). Verified against real words (らーめん → `raamen`, ちょっと → `chotto`,
including correct sokuon-into-"tch" handling) before wiring in, not just eyeballed.

**Part of speech: decided — add `word_frequency.part_of_speech`, filled by a one-time
local batch, not a live call.** Showing POS only for words with an existing `lemma` row
would leave most of a never-read page blank, which defeats the point of a browse page.
Calling Sudachi live per page view was rejected on two grounds: it would create a
`lemma` row for every word merely *viewed* (browsing should not silently accumulate the
same footprint as reading does — the exact behavior §9a's "no bootstrapping" decision
was protecting), and claude.md's hard rule keeps the NLP service off the read path
regardless.

Resolved all 9,944 words in one local batch (`V7__word_frequency_part_of_speech.sql`
adds the column; `V8__SeedWordFrequencyPartOfSpeech.java` loads it, same shape as V5) —
16 concurrent requests to the already-running local NLP service, 6 seconds total, not
run through a migration calling a live service (a migration shouldn't need another
service up to run — same reasoning as keeping this off the read path generally).
~1,774 of the 9,944 are multi-morpheme entries jpdb counts as one frequency unit but
Sudachi correctly splits (には → 助詞+助詞, でもない → 助詞+助詞+形容詞); their column
holds each token's top-level category joined with "+" rather than a single tag. 2
symbol-like entries (○, ヶ) resolved to no word token and are left null. Caught and
fixed the same hiragana/katakana mismatch as V6 before it could bite twice — the batch
script's output reading column had to be converted to katakana to match what V6 had
already done to the table, or V8's `UPDATE ... WHERE term = ? AND reading = ?` would
have silently matched nothing.

English display labels (Noun/Verb/Particle/etc., including for the "+"-joined
multi-token case) live in the frontend (`partOfSpeechLabel.ts`) — the backend keeps the
raw Sudachi tag, same "store the source data, derive the display form" pattern as
`dictionary_form`/`normalized_form`.

### Meaning, added to the browse page — 2026-08-23

**Decided: `word_frequency.meaning`, seeded once from JMdict.** Same shape as ranks
(V5) and part of speech (V8): a static one-time local batch, no live dependency.
Source is [jmdict-simplified](https://github.com/scriptin/jmdict-simplified)'s
"common-only" English JSON (JMdict itself is EDRDG's; CC BY-SA 4.0 / no restriction on
commercial use, actively maintained — weekly releases) rather than raw JMdict XML,
since the common-only English variant is already close to exactly the size and shape
needed (22,630 entries vs. our 9,944 words) and self-contained JSON, not XML with
custom entity references, to parse.

**Spot-check before committing (per the same discipline as source picks) found a real
mapping problem, not just a clean match.** JMdict's kana field is hiragana for native
words — consistent with jpdb, but the *reading* isn't the issue this time. The issue:
の has no kanji-less entry in JMdict at all — it exists only as a reading of two
unrelated kanji headwords (乃/之 = the possessive particle, 野/埜 = "field"), so a
naive kana-only lookup for a pure-kana top-ranked particle would be ambiguous between
unrelated words. Resolved by reusing V8's already-computed Sudachi part-of-speech to
disambiguate: prefer the JMdict sense whose POS agrees with what Sudachi resolved for
that word (の → 助詞/particle → picks the possessive-particle entry, not "field").

**Matching strategy:** kanji-exact match first (content words — 猫, 食べる, 難解: 6,005
of 7,567 kanji-bearing words, 79.4%, rising to 81.1% once the kana+POS fallback catches
kanji spellings whose exact text isn't in JMdict as-is). Kana match with POS
disambiguation as fallback, used both for pure-kana words and for the kanji-bearing
words that missed the direct match. Overall: **75.4% (7,500/9,944)** get a confident
meaning; the rest — mostly multi-morpheme entries jpdb counts as one frequency unit but
that don't correspond to a single dictionary headword (ような, なので, 気がする), the
same phenomenon V8's "+"-joined POS exists for — get no meaning rather than a guessed
one. Verified match quality by actually running the strategy against all 9,944 words
(not just eyeballing a handful) before writing any schema, per your explicit ask to see
coverage first.

Migrations: `V9__word_frequency_meaning.sql` (column), `V10__SeedWordFrequencyMeaning.java`
(loads `seed-data/jpdb_v2.2_freq_top10k_meaning.tsv`, same Java-migration shape as V5/V8).
Displayed in the click-to-expand status picker, not always-on in the grid — glosses run
long (も: "too; also; in addition; as well; (not) either (in a negative sentence)"),
so showing them under every word in a dense 100-word grid would hurt scannability more
than it'd help; romaji and POS stay always-visible since they're short.

### Status filter, added to the browse page — 2026-08-24

**Decided: offset pagination, not rank-band, and it applies uniformly whether or not a
filter is active.** The original `page=N` meant "ranks (N-1)*100+1..N*100" — fine
unfiltered, but a status filter matches an arbitrary, scattered subset of ranks, so
most rank-bands would come back empty under a filter. Switched
`VocabularyBrowseRepository` to a `WITH scored AS (...)` CTE (the same per-word status
computation as before) followed by an optional `WHERE status = ?` and
`ORDER BY rank LIMIT ? OFFSET ?` — one query shape for both cases, and unfiltered pages
are now always exactly 100 words (extending slightly past a round rank number to make
up the count, when the seed data's dedup has left a gap) rather than occasionally 99.

`GET /api/vocabulary?page=N&status=KNOWN` (status optional). Frontend: pill filter
buttons above the grid (All/New/Learning/Known/Ignored), state kept in the URL's search
params alongside `page` (bookmarkable, and resets to page 1 on filter change). The
status-change mutation still updates optimistically in place for instant feedback, but
now also invalidates the query on settle — under a filter, marking a word's status can
remove it from view entirely (a NEW-filtered word marked KNOWN should disappear), which
an in-place optimistic patch can't express on its own.

### Part-of-speech filter, combinable with status — 2026-08-24

**Decided: `word_frequency.pos_categories TEXT[]`, derived by pure SQL from the
existing `part_of_speech` column — no new Sudachi calls, unlike V8/V10.**
`part_of_speech` is either a full Sudachi tag (`動詞,一般,*,*,...`) or, for
multi-morpheme entries, several categories joined with `+` (`助詞+助詞` — see V8);
neither shape supports a plain equality filter. `V11__word_frequency_pos_categories.sql`
splits on `+`, takes each segment's text before its first comma, dedupes, and stores
the result as an array (`{動詞}`, `{助詞,形容詞}`) — verified against real rows (の →
`{助詞}`, でもない → `{助詞,形容詞}`) before writing the migration.

`GET /api/vocabulary?status=KNOWN&pos=動詞` — both filters are plain `AND`-ed
conditions on the same `scored` CTE (`VocabularyBrowseRepository`); `pos` isn't
validated against a known set, an unrecognized value just matches nothing. Frontend:
a dropdown (values are the raw Japanese category, labels are `partOfSpeechLabel.ts`'s
English names) next to the status pills, same URL-search-param pattern as `status`.

**Real bug caught while testing this, not by inspection.** The new integration test
initially failed with an empty result for `pos=動詞` even though a plain `curl` to the
identical URL worked. Root cause: `URLEncoder.encode`-ing the Japanese value by hand
before handing it to `TestRestTemplate.exchange(String, ...)` double-encodes it —
`exchange(String, ...)` runs its argument through Spring's own URI-template handling,
which doesn't know a `%E5%8B...` sequence is already encoded and encodes the `%`
again. Fixed by building the request with `UriComponentsBuilder` (against
`restTemplate.getRootUri()`) and calling `.encode()` exactly once, then passing the
resulting `URI` object — which bypasses the string-template path entirely — rather
than a hand-built query string.

---

## 14. Library page: delete, search, sort, pagination, last-opened — 2026-08-24

Backend, following up on the `ui.md` library-page suggestions:

- **`DELETE /api/texts/{id}`** — ownership-scoped the same way as `GET /{id}` (a
  text belonging to another user 404s, doesn't delete). `text_token` cascades via
  its existing FK (V1), so the endpoint is a single statement.
- **`?q=` / `?sort=DIFFICULTY|RECENT`** on `GET /api/texts` — title search
  (case-insensitive `ILIKE`) and a whitelisted sort enum (`TextSortOrder`), never a
  raw request string spliced into `ORDER BY`.
- **Pagination**, built ahead of an actual need at this app's scale (claude.md's own
  ceiling is "hundreds of documents") — built because explicitly requested, the same
  "not urgent, but you asked" distinction as the over-engineering review, not a
  reversal of it. Response shape changed from a bare array to
  `TextLibraryPageResponse(page, totalPages, texts)`, matching `VocabularyPageResponse`'s
  shape.
- **`text.last_opened_at`** (`V12`) — scoped deliberately small (a recency
  timestamp, not exact resume position) per the explicit choice between the two when
  this was proposed. Set by `GET /{id}` (opening, not importing, is what counts),
  read-only elsewhere.

**Two real bugs found by actually running the tests, not by inspection** — both
Spring Data JPA derived methods that mutate data outside a transaction:
`deleteByIdAndUserId` (a `deleteBy...` derived method) and `touchLastOpenedAt` (an
`@Modifying @Query` update) both threw `TransactionRequiredException` /
`InvalidDataAccessApiUsageException` the first time they actually ran, because
neither the repository method nor the calling controller action opens a transaction
on its own. Fixed by adding `@Transactional` directly on each repository interface
method — a case where a compiling, seemingly-correct-looking JPA repository method
was actually broken until exercised end to end.

Frontend: difficulty score is now a colored badge (bucketed easy/medium/hard,
reusing the reader's known/learning/new color variables) instead of plain text in
the meta line; `createdAt` and `lastOpenedAt` are shown as relative time
(`Intl.RelativeTimeFormat`, no new date library); a search box and a two-way sort
toggle (styled with the same pill button class as the vocabulary page's status
filter); a delete button per row behind a native `confirm()` dialog; pager UI
matching the vocabulary page's Prev/Next pattern. Full visual consistency with the
vocabulary page (`ui.md` item #6) was explicitly deferred, not attempted here.

---

## 15. Reader page: meaning/POS, real resume position, and reading UX — 2026-08-24

Backend:

- **Meaning/POS on every token** — `TextReadRepository`'s per-lemma query (already
  the one place that loads distinct-lemma status, decision #5) now also joins
  `word_frequency` by `lemma.dictionary_form`/`reading_form`, the exact same join
  shape as `TextLibraryRepository`/`VocabularyBrowseRepository`. This is the *easy*
  case of that join, unlike the vocabulary browse page: every token here already has
  a real `lemma` row from import, so there's no "lemma might not exist yet" fallback
  to handle.
- **Real resume position — the option deliberately left open when `last_opened_at`
  (§14) was scoped down to just a timestamp.** `text.last_read_position` (`V13`), a
  `text_token.position` value. Decided, when this came up again: save only when
  leaving the reader (not on every click, not via scroll-tracking) — same tradeoff
  shape as §14's scope-down, chosen for the same reason: the simpler option ships
  today, and periodic-autosave-via-scroll-position is still on the table later if
  click-driven saving turns out to miss too much passive reading.
- **`PUT /api/texts/{id}/position`** — ownership-scoped the same way as delete/list,
  called once by the frontend on unmount, not per click.

Frontend, on top of that data:

- Meaning + part-of-speech shown in each token's popover, above the status buttons —
  the exact same `status-picker__meaning`/`status-picker__pos` markup as the
  vocabulary page's `VocabularyWordChip`. `partOfSpeechLabel.ts` moved from
  `vocabulary/` to a new `shared/` folder since both pages need it now — the first
  thing in this codebase to cross that boundary.
- **A real, confirmed bug fixed, not just suspected**: paragraph breaks were being
  silently flattened. Checked by actually importing multi-line text and inspecting
  the token stream rather than guessing — the newline survives as its own
  `is_word: false` token (Sudachi doesn't consume it), so the fix was purely
  `white-space: pre-wrap` on `.reader-tokens`; the data was never the problem.
- Number-key shortcuts (1–4, matching each popover's status order) while a token's
  picker is open, scoped narrowly enough that typing elsewhere never gets
  intercepted.
- A small persistent color legend and a live "N known · N learning · N new" tally
  computed client-side from the already-loaded token list (distinct lemmas, not raw
  token counts — consistent with how the rest of the app counts vocabulary).
- Font stack gained Japanese-specific fallbacks (`Hiragino Kaku Gothic ProN`, `Yu
  Gothic`) ahead of the generic `sans-serif`.
- Resuming a saved position scrolls the matching token into view once, the first
  time a text's tokens load — not on every refetch after a status change.

---

## 16. Script-variant normalization for word_frequency — 2026-08-25

**The problem, recurring across three separate features:** できる and 出来る are the
same word (Sudachi's own `normalized_form` already agrees — verified directly against
the real service before touching anything: both resolve to `出来る`, same reading,
same part-of-speech). But `word_frequency` had two separate rows for them (rank 94 vs.
1302), and every join from `lemma` to `word_frequency` (difficulty scoring §9d, the
vocabulary browse page §13, the reader's meaning/POS §15) matched on
`lemma.dictionary_form` — the *displayed* spelling, first-write-wins per decision #1,
not the word's real identity. The same word silently got a different difficulty
weight, meaning, or status display depending on which spelling happened to appear in
a given text. Not a rejected idea revisited — this was flagged as a known limitation
in §9b/§13/§15 each time it came up, deferred rather than fixed, until now.

**Decided: dedupe `word_frequency` itself down to one row per real word, keyed on
`(normalized_form, reading)` — not a query-time "pick the best of several candidates"
pattern.** `normalized_form` (V14) was filled the same way as V8/V10 — a one-time
local batch through the real NLP service, no live dependency. Real data turned up far
more than できる/出来る: **498 duplicate groups, 545 redundant rows** in the actual
top 10k — ある/在る/有る, なる/成る, いる/居る, 思う/想う, 言う/云う, ない/無い among
them. V16 keeps the better (lower) rank per group — 9,944 rows became 9,399 — and adds
`UNIQUE (normalized_form, reading)`, the same real-constraint discipline as `lemma`'s
own `(normalized_form, part_of_speech)` key, which also makes the new join an indexed
lookup instead of a sequential scan.

All three consuming queries (`TextLibraryRepository`, `VocabularyBrowseRepository`,
`TextReadRepository`) switched their join from `dictionary_form`/`reading_form` to
`normalized_form`/`reading_form`. Verified live against the running app, not just by
reading the diff: imported text using 出来る (the spelling whose own `word_frequency`
row V16 deleted) — its reader token still resolved real meaning/POS via できる's
surviving row; marking that same lemma KNOWN correctly showed できる as KNOWN on the
vocabulary browse page. Both would have silently broken (null meaning, or the wrong
lemma never found) under the old dictionary_form-based join now that word_frequency
no longer has a row for every spelling.

---

## 17. "Other words" list on the vocabulary page — 2026-08-31

**The ask:** the vocabulary browse page (§13) only ever shows words *inside* the top
10k frequency list — asked directly what happens to a lemma from an imported text that
falls outside it (rank > 10,000, or simply never in the source frequency data at all):
it still gets a real `lemma` row and can still be marked KNOWN/LEARNING/etc. via the
reader, but it was invisible on the vocabulary page, with no way to browse or triage it
there. Decided: a second, explicitly separate list on the same page — not merged into
the ranked grid, since these words have neither a rank nor a `word_frequency`-sourced
meaning to show.

**"Actually encountered" is per-user, not global** — sourced from `text.lemma_ids` (the
same ownership notion the difficulty query already uses), not every `lemma` row that
has ever existed across all users. Sorted by `lemma.created_at DESC` (most recently
encountered first) — the closest available proxy to "when did I read this," since
lemma rows are global and not per-user-timestamped.

**Implementation:** `OtherVocabularyRepository` (JdbcTemplate, offset pagination, same
status/POS-category filters as the ranked list) selects lemmas the user has encountered
whose `(normalized_form, reading)` has no match in `word_frequency` — i.e., the exact
complement of the join added in §16. `GET /api/vocabulary/other` mirrors the shape of
`GET /api/vocabulary`. No find-or-create status endpoint needed here (unlike
`PUT /api/vocabulary/status`): every word on this list already has a real lemma row by
construction, so status changes go straight to the existing
`PUT /api/lemmas/{id}/status`. Frontend: `VocabularyPage` gained a `tab` search param
("top" | "other") switching between the existing ranked grid and a new
`OtherVocabularyWordChip` (same chip shape, minus rank and meaning), sharing the
status/POS filter bar and pager between both tabs.

**Bug found and fixed while smoke-testing this, not introduced by it:** live-testing
against real imported text (聞いていた, containing conjugated 居る) showed some very
common words — た, て, いる itself — landing on the "other" list when they shouldn't
be. Root cause, confirmed by tracing the import path: `lemma.reading_form` is
first-write-wins per `(normalized_form, part_of_speech)`, same as `dictionary_form` —
but unlike `dictionary_form`, its value is whatever Sudachi returned for the specific
*surface occurrence* first encountered, not the dictionary form's own reading. For
いる conjugated as い+た, that's い's reading (イ), not いる's own reading (イル,
`word_frequency`'s row). §16's join keys on `(normalized_form, reading)`, so that
mismatch silently dropped the match — a pre-existing gap in the same join used by
§16 (`TextLibraryRepository`, `TextReadRepository`), not new to this feature, just
newly visible because this feature's whole purpose is showing "what didn't match."
The NLP contract has no separate "dictionary-form reading" field to fall back on
(confirmed against the generated OpenAPI client) — Sudachi's reading is inherently
per-surface-occurrence.

**Fix, two parts, both landed 2026-08-31:**
1. **Forward (`TextImportService.distinctWordCandidates`):** prefer an occurrence
   where the surface text equals the dictionary form (i.e., the word appeared
   unconjugated somewhere in the text) over one where it didn't, keeping
   `dictionary_form` itself untouched (still first-write-wins, for the same reason
   §16 already documents — a later occurrence can be a different script variant).
   Zero extra NLP calls; a same-document re-prioritization of tokens already
   returned by the one call import already makes. Residual gap: a word that is
   *always* conjugated in every text ever imported still gets a wrong reading —
   accepted as the boring-solution tradeoff over adding a second synchronous NLP
   call per newly-discovered word.
2. **Backfill (`V17__fix_lemma_reading_form_from_word_frequency.sql`):** corrects
   existing rows from `word_frequency`'s already-correct, already-deduped (§16)
   readings, in two tiers — exact `dictionary_form`/`term` match first (needed
   because some kanji genuinely have multiple unrelated readings sharing one
   `normalized_form`, e.g. 居る is both いる/イル and おる/オル — normalized_form
   alone can't disambiguate, but the lemma's own spelling can), falling back to
   "the normalized_form has only one reading in word_frequency anyway" for script
   variants whose own row §16 deleted as a duplicate (出来る has no surviving
   `term` row, but 出来る's normalized_form still resolves unambiguously).

**Verified live, not just by reading the diff:** re-imported the same sentence after
rebuilding — いる (lemma id 6) now correctly resolves as テ→イル and no longer
appears on the "other" list.

**Confirmed out of scope, found while verifying the fix, left alone:** た and て
still appear on the "other" list after the fix, for two unrelated reasons, not a
gap in this fix — た has no `word_frequency` row at all (missing from the jpdb
source data as its own entry, not a join bug), and て's own `word_frequency` row
carries `normalized_form = で` — a pre-existing inconsistency in the V15 seed data
between how Sudachi normalizes て in isolation (offline batch) versus in a full
sentence (live import), not something a `lemma`-side migration can correct.

---

## 18. Richer definitions and example sentences, decoupled from the top 10k — 2026-08-31

**The ask:** §17's "other words" list had no definitions at all (word_frequency,
where meaning lived, has no row for anything outside the top 10k by construction),
and even the ranked list's definitions felt thin — one collapsed JMdict gloss per
word. Both are the same underlying gap: `word_frequency.meaning` (§13) was only
ever matched against the fixed top-10k jpdb term list, using JMdict data that
itself covers far more ground.

**Decided: two new tables, decoupled entirely from `word_frequency`'s rank-only
job.** `dictionary_entry(normalized_form, reading, senses text[])` — one row per
word, every JMdict sense kept, not collapsed to one — and
`word_example(normalized_form, reading, japanese_text, english_text)` — one
precomputed best (shortest) example sentence per word, no raw sentence corpus or
link table kept at all. Both key on `(normalized_form, reading)`, the same
post-§16 join key every lemma-joining query already uses, so `VocabularyBrowseRepository`,
`OtherVocabularyRepository`, and `TextReadRepository` each pick these up with one
more `LEFT JOIN`. `word_frequency` itself is untouched — it keeps doing the one
job it's actually for (difficulty scoring's rank).

**Sources, both static and offline, no new live dependency:** JMdict via
scriptin/jmdict-simplified's `eng-common` release (EDRDG, CC BY-SA 4.0) — 22,636
entries, resolved down to **20,686** `dictionary_entry` rows by tokenizing each
entry's own headword once through the local NLP service (same bridge V15 built
for `word_frequency.normalized_form`); entries whose headword didn't tokenize to
exactly one word token spanning the whole string were skipped rather than guessed
at (91.4% resolution). Tatoeba via manythings.org/anki's pre-filtered `jpn-eng`
pairs (CC BY 2.0 FR) — 117,022 sentences, tokenized the same way, reduced to
**16,612** `word_example` rows (one per distinct word actually covered).

**A second instance of §17's exact bug, caught before shipping, not after:** the
first pass at `word_example` keyed each sentence's words by Sudachi's per-token
reading — the same mistake `lemma.reading_form` made. 出来る conjugated three
different ways across three different sentences produced three different, wrong
keys (デキル, デキ, デキレ) instead of one correct デキル, none of which would
have matched `lemma.reading_form` after §17's fix. Caught by spot-checking 出来る
in the generated seed file before writing the migration, not by a user report this
time. Fixed in the seeding script itself: canonicalize every token's reading against
`dictionary_entry`'s already-correct headword-derived reading for that
`normalized_form` (falling back to the raw per-token reading only for a word
outside that seed entirely), then require an unconjugated surface occurrence
before trusting a specific reading for a genuine homograph (居る is both いる and
おる) — the same two-tier logic §17's `V17` migration already used. Separately,
the NLP service itself got overwhelmed under 24-way concurrency (`ConnectionResetError`
on ~6% of the first pass) — fixed operationally by retrying the failures at lower
concurrency (4, then 2 threads) until the batch was clean, not by silently
accepting the gap.

**Verified live, not just by reading the diff:** re-imported いざこざ ("trouble;
quarrel," genuinely outside the top 10k) and confirmed `/api/vocabulary/other`
returns its real multi-sense definition and a real example sentence, not the old
"no definition available" placeholder. Re-checked の (rank 1) on the ranked list
too — five real senses now, not word_frequency's old single collapsed gloss.

**Confirmed, honest residual gap:** 瑣末 (used as this session's worked example
for "outside the top 10k") is outside JMdict's *common* subset too, not just the
frequency list — it still shows "no definition found." No seeding strategy over
a finite dictionary closes this completely; re-running the offline seed against a
newer JMdict release later would track its own updates, but a rarer word than the
"common" cut simply won't have an entry until (or unless) it's added there.

Attribution for both sources (required by CC BY-SA 4.0 and CC BY 2.0 FR, and not
shown anywhere in the UI before this) now appears as a small footer on every
authenticated page (`App.tsx`'s `Footer`).

**Follow-up, same day:** the small absolutely-positioned popover the reader and
both vocabulary pages shared (§13, §15) had no real room for a senses list plus
an example sentence — reported directly as "the space is very small right now."
Replaced with a shared `WordDetailModal` (a centered, backdrop-covered card,
`frontend/src/shared/WordDetailModal.tsx`) used by `TokenSpan`,
`VocabularyWordChip`, and `OtherVocabularyWordChip` alike, rather than tripling
a modal implementation across all three. Closes on backdrop click, an explicit
×, or Escape (new — the old popover only closed on an outside click).
`DefinitionBlock` (senses/example markup) is unchanged; only its container
became a modal instead of a corner-anchored popover.

---

## 19. Statistics page — 2026-08-31

**The ask:** a page showing how many words are known/learning/etc., broken down
by the top 10k frequency list versus everything else encountered — the two
categories §17 already established as separate lists, now aggregated instead of
listed.

**Decided: one new endpoint, `GET /api/vocabulary/stats`, reusing the exact
status computation the two existing list endpoints already do — grouped, not
listed.** `VocabularyStatsRepository` runs the same `word_frequency`/lemma
LATERAL-join CTE `VocabularyBrowseRepository` uses for the top-10k side, and the
same `text.lemma_ids`-owned CTE `OtherVocabularyRepository` uses for the "other"
side, but each ends in `GROUP BY status` instead of `ORDER BY ... LIMIT/OFFSET`
— one query per category, not one row per word. Deliberately its own repository
rather than added to either existing one: neither list needs pagination or the
senses/example joins for a stats page that only ever shows four numbers per
category.

**Frontend:** `StatisticsPage.tsx` at `/stats`, linked from the header nav.
Three summary cards (combined known, combined learning, total "other" words
encountered) plus a per-category breakdown — a CSS stacked bar (reusing the
same `--color-new/learning/known/ignored` variables the reader's status legend
already defines) and a count list per status. No charting library — four
segments in a row is simpler as a few styled `div`s than a dependency.

**Verified live:** fresh user shows 9,399 top-10k words all NEW (word_frequency's
real post-§16-dedup row count, not the nominal "10,000") and zero other words;
marking の KNOWN and importing+marking a single outside-the-list word shifted
exactly one count in each category, nothing else.

---

## 20. Naming and renaming imported texts — 2026-09-01

**The ask:** set a title at import time, and change it afterward — import
already accepted an optional `title` (falling back to a derived one,
`TextImportService.deriveTitle`), but the frontend never exposed the field, and
there was no way to rename a text once imported at all.

**Import page:** a plain text input above the paste box, sent as `importText`'s
existing optional second argument — no backend change needed here, the gap was
only that the frontend never asked.

**Renaming:** new `PUT /api/texts/{id}/title`, mirroring `PUT /api/texts/{id}/position`'s
exact shape — same ownership-scoped 404 (a rename attempt on someone else's
text is indistinguishable from one on a nonexistent id), same
`@Modifying`/`@Transactional` derived-query pattern on `TextDocumentRepository`.
Blank is rejected (`@NotBlank`) here, unlike import's optional title — a rename
is an explicit action, not a fallback that should silently re-derive a title
from the body.

**Where to rename from:** the reader page's `<h1>` — click the title, it
becomes an input, Enter/blur saves, Escape cancels. Chosen over adding the same
affordance to the library list too: the list's title is already wrapped in a
`<Link>` to open the text, so a second click target on the same row would need
its own hit-testing; the reader already shows the title on its own line with
nothing else competing for that click. Optimistic on the reader's own cache;
the library list's cache is invalidated rather than patched, since the library
page isn't guaranteed to be mounted with this exact text on its current page.

---

## 21. Furigana in the reader, dark mode, and non-color status signaling — 2026-09-01

**Furigana (claude.md's reading-disambiguation problem, Slice 5's last open
item):** `TokenSpan` already had the per-sentence-correct reading on every
token (design.md §15) — it was only ever shown as an invisible hover `title`.
Rendered as real `<ruby>`/`<rt>` now, shown only when the surface text actually
contains a kanji (`hasKanji.ts`, a small Unicode-range check) — a kana-only
word (かんしゃく) reading itself back above itself in the same kana would just
be noise. `.reader-tokens`'s line-height went from 2.4 to 2.8 to give the
ruby annotation room without clipping or overlapping the next line.

**Toggleable, added same day:** a per-viewer display preference, not app
state — a plain toggle button in the reader toolbar (reusing the existing
`.vocabulary-filter` pill styling rather than inventing a new control), backed
by `localStorage` rather than the backend. This isn't data that needs to sync
across devices or survive a logout, so there's no `user_preference` table or
API call here — just `kotanoba:show-furigana` read once on mount and written
on toggle, wrapped in try/catch (a private window or blocked storage should
degrade to "doesn't persist," not a broken toggle). `.reader-tokens--no-
furigana` drops the line-height back to 2.4 when it's off, so hiding furigana
doesn't leave the text looking oddly double-spaced.

**Dark mode:** `prefers-color-scheme: dark`, no manual toggle. What made this
more than the "close to free" swap ui.md originally scoped (written before
this session's word-detail modal, stats page, and definition popovers existed)
was literal `white`/hex colors scattered across a dozen components by then —
replaced with semantic custom properties (`--color-surface`, `--color-text-
secondary`, `--color-text-muted`, `--color-subtle`, `--color-error`) redefined
once under the media query, rather than page background/text alone. One real
bug caught by actually reasoning through it, not by eye: `.difficulty-badge`'s
text color mixed each status color toward literal `black` for contrast against
its light background tint — correct in light mode, but wrong in dark mode, where
that same tint (still alpha-blended, now over a dark surface) reads as *dark*,
and black text on a dark tint has no contrast at all. Fixed with a
`--color-badge-contrast` variable (black in light mode, white in dark) instead
of a literal.

**Non-color status signal (ui.md #16):** `.token--new/learning/known/ignored`
now differ by `border-bottom-style` (solid/dashed/none/dotted), not just hue —
readable on a colorblind or grayscale display. `known` staying unmarked is
deliberate, not an oversight: "no decoration" is itself a fourth
color-independent state, and it keeps known words visually quiet while
reading, which was already the intent. Checked the other half of ui.md #16's
concern (status-picker buttons needing labels/keyboard reachability) and found
it already satisfied — every status button already renders its status name as
visible text, and nothing in the CSS suppresses the browser's default focus
ring — so no changes were needed there.

---

## 22. Making Spring Modulith, actuator, and API docs real — 2026-09-01

**The ask:** the project is also a resume piece specifically meant to
demonstrate Spring Boot depth — worth periodically checking that what
claude.md *claims* the backend does is actually true, not just declared.

**Spring Modulith was a dependency, not an enforced boundary.**
`spring-modulith-starter-core`/`-test` were in `pom.xml` ("enforces the
module boundaries claude.md requires," the comment said) with nothing ever
calling `ApplicationModules.verify()`. Added `ModularityTests` to make that
literal. First run failed immediately — not with a real violation, but
`ArchUnit`'s bundled ASM choking on Java 25's class file version ("Unsupported
class file major version 69"). `spring-modulith.version` was 1.3.6, from
before this project's Java 25 choice; **1.4.13** (the current 1.4.x release —
confirmed via Maven Central's actual metadata, not a blog post, since several
turned out to describe an unrelated 2.x line that requires Spring Boot 4) ships
a newer ArchUnit that parses Java 25 fine. After the upgrade, `verify()`
passed cleanly on the very first real run: no cycles between `text`/`lemma`/
`user`/`nlp`, nothing reaching into another module's internals. Kept as a real
test, not a one-off check — a future change that introduces a cycle now fails
the build instead of getting caught in review, if at all.

**Actuator and OpenAPI docs, both real gaps, not partial.** There was already
a hand-rolled `GET /health` (`HealthController`) — that's Render's
`healthCheckPath` contract and stays exactly as-is, not replaced. Actuator is
additive: `/actuator/health`, `/actuator/info`, `/actuator/metrics`, nothing
else — `management.endpoints.web.exposure.include` is the only thing
separating those from `/actuator/env`/`/actuator/beans`, which can leak
`JWT_SECRET`/DB credentials and stay off even though `SecurityConfig`
`permitAll`s the whole `/actuator/**` path rather than gating per-endpoint.
`/actuator/info` needed `management.info.env.enabled: true` on top of that —
off by default in Spring Boot specifically so an app doesn't accidentally
dump its environment into a public endpoint; safe here because it only
surfaces the `info.app.*` block, nothing env-derived. springdoc
(`2.8.6` — the 2.x line, not 3.x, which targets Spring Boot 4) gives a real
`/swagger-ui/index.html` with a bearer-JWT "Authorize" button
(`OpenApiConfig`), rather than requiring a reviewer to read controller source
to know the API shape.

**Verified live, all three:** `/actuator/health` returns `{"status":"UP"}`
with no component detail; `/actuator/env` and `/actuator/beans` both 404 (not
just unauthorized — genuinely not exposed); `/swagger-ui/index.html` and
`/v3/api-docs` both load with the custom title/description, 14 discovered
paths; a full register → authenticated-request round trip still works
unchanged after all three additions.

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
- [x] Frequency reference source: jpdb v2.2, top 10k, spot-checked against Sudachi (decision #9b)
- [x] Frequency reference schema placement: separate `word_frequency` table, keyed by term/reading text, replace-only (decision #9b)
- [x] Seed migration for top 10k frequency-ranked lemmas — `V4__word_frequency.sql` (DDL, reviewed and signed off) + `V5__SeedWordFrequency.java` (Java Flyway migration loading `seed-data/jpdb_v2.2_freq_top10k.tsv`, 9,944 rows). Verified end to end against a real Postgres: correct row count, correct encoding, correct ranks.
- [x] Plain SQL difficulty-scoring query — `TextLibraryRepository` (design.md §9d formula: `1/ln(rank + 1)` weighting, 0.05 floor for unranked lemmas). Caught and fixed a real ambiguous-column bug (`lemma_id` alias collision) by actually running it, not just reading it.
- [ ] ~~Redis caching of user + text bitmaps~~ — rejected, no cache layer at all
- [x] Library view sorted by frequency-weighted difficulty — `GET /api/texts` now runs `TextLibraryRepository.listForUser`, one query computing and sorting by score (decision #10: "moves to SQL in Slice 3"), replacing the old JPA `findByUserIdOrderByCreatedAtDesc`. Frontend shows "N% known" per text. Covered by a new integration test (`libraryListIsSortedByFrequencyWeightedDifficultyDescending`) — 5/5 tests pass against real Testcontainers Postgres + NLP.
- [ ] Deployed

### Slice 4 — SRS review mode
- [ ] FSRS implementation (not SM-2)
- [ ] New-card introduction ordered by frequency rank (decision #9)
- [ ] Review queue UI
- [ ] Deployed

### Slice 5 — reading disambiguation & script-variant normalization
- [x] Furigana display from tokenizer reading output — §21, `<ruby>`/`<rt>` in the reader, kanji-only tokens
- [x] Script-variant lemma merge (`できる`/`出来る`) — §16
- [ ] Deployed

### Slice 6 — audio-text alignment
- [ ] WhisperX integration
- [ ] Sentence-level click-to-replay
- [ ] Deployed

---