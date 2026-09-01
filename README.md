# Kotanoba

A LingQ-style Japanese reading trainer: import authentic text, read it with
per-word status tracking (New → Learning → Known → Ignored), and browse
vocabulary backed by real dictionary data. Built as a portfolio project — and
in genuine daily use by its own author to learn Japanese, which is why most
of the harder engineering decisions below trace back to a real reading
session, not a hypothetical one.

**Live:** https://kotanoba.vercel.app
**API docs:** `/swagger-ui/index.html` on the deployed backend (bearer-JWT
"Authorize" button included — register via `/api/auth/register` first)
**Full decision log:** [`design.md`](design.md) — every architectural choice
here, including the infrastructure this project deliberately chose *not* to
build, with the reasoning and (where it matters) real measured numbers, not
just intentions. [`claude.md`](claude.md) is the standing project brief.

## What it does

- **Import** — paste Japanese text; a FastAPI + Sudachi sidecar tokenizes it
  synchronously, and every distinct word becomes a real, trackable lemma.
- **Read** — click any word for its reading (furigana, toggleable), part of
  speech, every dictionary sense (not collapsed to one gloss), and a real
  example sentence — then mark it New/Learning/Known/Ignored. Status attaches
  to the *dictionary form*, not the surface spelling: marking 食べる known
  also covers 食べます, 食べた, 食べられる, etc., and script variants
  (できる/出来る) share one status rather than being tracked separately.
- **Browse vocabulary** — the top 10,000 most common words, ranked by
  frequency, filterable by status and part of speech, plus a separate list
  for every word you've actually encountered outside that top 10,000.
- **Track progress** — known/learning/new/ignored counts, split between the
  top-10k list and everything else you've read.
- **Library** — search, sort by reading difficulty (frequency-weighted, not
  just word count) or recency, rename a text after import.
- Dark mode (OS-driven, no toggle to maintain), and status is never signaled
  by color alone — each status also has its own underline style.

## Architecture

```
React + TS (Vercel) ──HTTPS──▶ Spring Boot (Render) ──JDBC──▶ Postgres (Neon)
                                       │
                                       │ HTTPS (shared-secret header)
                                       ▼
                              FastAPI + Sudachi (Render)
```

The frontend never talks to the tokenizer directly — everything goes through
Spring Boot. The tokenizer is a pure function: no database, no concept of a
user, called synchronously only from the import request, never on the read
path. It's also the only genuinely external call in the request lifecycle,
which is why it's the one place wrapped in Resilience4j (timeout, retry,
circuit breaker) rather than trusted to just work.

| Layer | Stack |
|---|---|
| Backend | Spring Boot 3.5, Java 25, Spring Security (JWT + refresh rotation), Spring Data JPA + JdbcTemplate, Spring Modulith, Resilience4j, Flyway |
| Database | PostgreSQL 16 |
| NLP sidecar | FastAPI + Sudachi (Python), OpenAPI schema consumed by a generated Java client |
| Frontend | React + TypeScript + Vite, TanStack Query |
| Data sources | [JMdict](https://www.edrdg.org/jmdict/j_jmdict.html) (definitions, CC BY-SA 4.0), [Tatoeba](https://tatoeba.org) (example sentences, CC BY 2.0 FR), jpdb.io word-frequency list |
| Deployed on | Neon (Postgres, free tier) · Render (backend + NLP, free tier) · Vercel (frontend) |
| Local dev | Docker Compose — one command starts all three services |

## Engineering highlights

A few decisions worth reading the reasoning for (all in `design.md`, linked):

- **Two queries, not N+1, on the one path that matters.** Opening a
  2,000-token article costs one ordered scan for the tokens plus one indexed
  lookup for its ~700 distinct lemmas' status — merged in Java, never one
  status lookup per token. §5.
- **JPA for CRUD, raw SQL where the ORM actively fights you.** Marking a word
  known is a genuine upsert (no row the first time, a row every time after);
  expressed as JPA find-then-save it's two round trips and a race. One
  `INSERT ... ON CONFLICT` instead. §10.
- **Enforced module boundaries, not just a package convention.**
  `ModularityTests` calls Spring Modulith's `ApplicationModules.verify()` in
  CI — a cross-module dependency cycle fails the build, it isn't left to code
  review to catch. §22.
- **A second real bug caught by testing the fix, not just writing it.**
  Normalizing script variants (できる/出来る) surfaced that `lemma.reading_form`
  could silently pick a conjugated stem's reading instead of the dictionary
  form's own — and the fix for *that* was verified by finding the same class
  of bug already baked into a brand-new seed file, before it shipped. §17–18.
- **Infrastructure rejected on purpose, not skipped by accident.** A durable
  multi-worker import queue (`SKIP LOCKED`, three workers) and Redis-backed
  bitmap caching for difficulty scoring were both in the original plan and
  both dropped once actual scale was pinned down (single user, hundreds of
  documents) — "good for interviews" was explicitly ruled insufficient
  justification on its own. §7, §12.

## Running it locally

```bash
docker compose up
```

Starts Postgres, the NLP sidecar, and the backend together (health-check
gated startup order). Then, separately:

```bash
cd frontend
npm install
npm run dev
```

## Testing

```bash
cd backend && mvn test   # Testcontainers — real Postgres, real Sudachi call, no mocks on the integration path
cd nlp && python -m pytest
cd frontend && npm run lint && npx tsc -b   # no test suite yet — see design.md's known gaps
```

## Benchmarks

Recorded as measured, per claude.md's engineering standards — not estimated.

### Tokenizer

Apple Silicon, Python 3.9, `sudachidict-core`, split mode C.

| Measurement | Result |
|---|---|
| Dictionary cold load | 7 ms (once per process, at startup) |
| Tokenize 1,428-char article | 1.7 ms median (888 tokens) |
| Throughput | ~850,000 chars/sec |

The architecture assumed tokenizer speed would be irrelevant — it's called
once per document, synchronously, but off the interactive read path. That
held: a full article costs under 2 ms, so dictionary quality was correctly
the deciding factor over throughput.

### Not yet measured

claude.md calls for a `saveAll()` vs. JDBC-batch comparison for
`text_token` persistence as a first benchmark entry — the *reasoning* for
using JDBC batch is implemented and documented (`TextTokenBatchWriter`,
design.md §10), but the head-to-head timing itself hasn't actually been run.
Noted here rather than implied, in the same spirit as design.md's other
"confirmed gap, not silently patched" entries.

## Out of scope, deliberately

Multi-language support, social features, mobile apps, Kubernetes,
microservices beyond the one NLP sidecar, a custom auth provider, and (as of
2026-08-25) an SRS/Anki-style review mode — vocabulary status itself is the
review surface. See `claude.md`'s "Out of scope" section for the full list
and reasoning.
