# Kotanoba — frontend

React + TypeScript + Vite, talking only to the Spring Boot backend — never
directly to the NLP sidecar (see the root [`README.md`](../README.md) and
[`claude.md`](../claude.md) for why). TanStack Query owns all server state;
there's no separate client-side store.

## Running it

```bash
npm install
npm run dev
```

Expects the backend at `http://localhost:8080` by default (Vite's dev proxy
makes this same-origin, so no CORS setup is needed locally — see
`vite.config.ts`). Point it at a different backend with `VITE_API_BASE_URL`.

## Structure

| Directory | What's in it |
|---|---|
| `src/texts/` | Import, library, and reader pages |
| `src/vocabulary/` | Vocabulary browse, "other words," and statistics pages |
| `src/shared/` | Cross-page pieces — the word-detail modal, definition rendering, part-of-speech labels |
| `src/api/` | Typed fetch wrappers, one file per backend resource |
| `src/auth/` | Login and the JWT/refresh-token context |

## Testing

`npm run lint` (oxlint) and `npx tsc -b` are the only checks that currently
run — there's no component/unit test suite yet. Everything on this side has
been verified through the running app (backend integration tests plus live
browser/curl checks) rather than isolated frontend tests; see `design.md`'s
suggested next steps for closing that gap.
