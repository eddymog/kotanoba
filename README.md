# Kotanoba

A LingQ-style reading trainer for Japanese. Import authentic text, read it with
per-word status tracking, and review vocabulary with spaced repetition.

See [`claude.md`](claude.md) for architecture and [`design.md`](design.md) for
decisions and progress.

## Status

Slice 1 in progress. All Slice 1 design decisions are closed; see the progress
checklist in `design.md`.

| Component | State |
|---|---|
| `nlp/` — FastAPI + Sudachi tokenizer | Working, 24 tests green |
| `backend/` — Spring Boot | V1 Flyway migration only |
| `frontend/` — React + Vite | Not started |

## The NLP service

Stateless. No database, no user concept, never on the read path — called only by
the import worker. Text in, tokens out.

```bash
cd nlp
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest tests/ -q
.venv/bin/python -m uvicorn app.main:app --port 8000
```

```bash
curl -X POST localhost:8000/tokenize \
  -H 'Content-Type: application/json' \
  -d '{"text":"日本語を勉強しています。"}'
```

## Benchmarks

Recorded as measured, per the engineering standards in `claude.md`.

### Tokenizer

Apple Silicon, Python 3.9, `sudachidict-core`, split mode C.

| Measurement | Result |
|---|---|
| Dictionary cold load | 7 ms (once per process, at startup) |
| Tokenize 1,428-char article | 1.7 ms median (888 tokens) |
| Throughput | ~850,000 chars/sec |

The architecture assumed tokenizer speed would be irrelevant — it is called once
per document, asynchronously, off the read path. That held: a full article costs
under 2 ms, so dictionary quality was correctly the deciding factor rather than
throughput.

### Pending

- [ ] `saveAll()` vs. JDBC batch for token persistence
- [ ] Read-path query vs. naive per-token status lookup
- [ ] RoaringBitmap intersection vs. SQL set difference for difficulty scoring
