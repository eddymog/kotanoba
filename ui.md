# Kotanoba — UI improvement ideas

Working notes on what would make the UI better, based on reading the actual current
components (`App.tsx`, `LibraryPage.tsx`, `ImportPage.tsx`, `ReaderPage.tsx`,
`TokenSpan.tsx`, `index.css`) as of 2026-08-23. Right now the UI is Slice 1–3's thin
vertical slice: functional, unstyled beyond basics, no dead ends, no polish yet. That's
the right call for getting a slice shipped — this is the punch list for widening it.

Grouped by area, roughly in priority order within each group. "Now" means pure
frontend, no backend change needed. "Needs backend" means it depends on data the API
doesn't return yet. "Slice N" means claude.md already scopes the underlying feature —
these are about *how* to present it, not whether to build it.

---

## Reader page — the highest-value surface, since it's what you'll use daily

**All seven items below are done as of 2026-08-25 — see design.md §15.**

1. **[Done]** ~~A meaning, not just a reading.~~ Shown in each token's popover,
   sourced from the same JMdict-backed `word_frequency` data as the vocabulary page —
   the easy case of that join, since every reader token already has a real `lemma`
   row.
2. **[Done, folded into #1]** ~~Separate "show info" from "set status."~~ Not built as
   a distinct interaction mode — putting the meaning/POS at the top of the same
   popover, above the status buttons (matching `VocabularyWordChip`'s layout),
   addressed the underlying concern without needing two click zones.
3. **[Done]** ~~A color legend.~~ Small persistent legend in the reader toolbar — not
   collapsible, kept simple.
4. **[Done]** ~~Keyboard shortcuts for status.~~ Number keys 1–4 while a token's
   picker is open, labeled with `<kbd>` hints on the status buttons themselves.
5. **[Done]** ~~Running progress while reading.~~ A live "N known · N learning · N
   new" tally in the toolbar, computed client-side from the loaded tokens (distinct
   lemmas, not raw token counts).
6. **[Done, confirmed as a real bug]** ~~Preserve paragraph structure.~~ Verified by
   actually importing multi-line text and inspecting the token stream: the newline
   survives as its own token, so this was a pure CSS fix (`white-space: pre-wrap`),
   not a data problem.
7. **[Done]** ~~Font stack for Japanese.~~ Added `Hiragino Kaku Gothic ProN`/`Yu
   Gothic` ahead of the generic `sans-serif`.

Also landed alongside these, not on the original list: real resume-to-exact-position
(`text.last_read_position`, saved on leaving the reader) — the option explicitly
deferred when `last_opened_at` (library page, item #9) was scoped down to just a
timestamp.

## Library page — "what should I read next" is the whole point of Slice 3

8. **[Done, 2026-08-24]** ~~Make the difficulty score visual, not just numeric.~~
   Colored badge (easy/medium/hard, reusing the reader's status colors) — see
   design.md §14.
9. **[Done, 2026-08-24, scoped down]** ~~Resume / progress-through-text indicator.~~
   Built the recency half only (`last_opened_at`, shown as relative time) —
   deliberately not exact resume position, a bigger, separate decision left for
   later if it turns out to matter. See design.md §14.
10. **[Done, 2026-08-24]** ~~Search / filter.~~ Title search (`?q=`, case-insensitive).
    A difficulty-range filter is still open if it turns out to matter.
11. **[Done, 2026-08-24]** ~~Sort toggle.~~ `?sort=DIFFICULTY|RECENT`, difficulty
    stays the default.
12. **[Done, 2026-08-24]** ~~Delete/archive a text.~~ Delete only (no archive/undo) —
    a native `confirm()` dialog, no soft-delete. Worth a real undo affordance later
    if a misclick ever actually costs a text.

## Import page

13. **Loading feedback proportional to reality.** "Tokenizing..." plus a disabled
    button is honest but thin, and claude.md is explicit that the NLP service is on a
    free tier that can cold-start — a paste could legitimately hang for several
    seconds. A spinner or elapsed-time tick would keep that from reading as "did this
    break," which matters more here than on a typical form submit.
14. **Show what you're about to get.** No preview of tokenization, word count, or
    estimated difficulty before committing the import. Even a rough client-side word
    count (character count / heuristic) as you type would help; a real
    pre-import difficulty estimate is **needs backend** and probably not worth it
    until Slice 3's query is being reused elsewhere anyway.

## Cross-cutting

15. **[Done, 2026-09-01]** ~~Dark mode.~~ `prefers-color-scheme: dark` — turned
    out to need more than the original "redefine bg/text/border" scope once the
    modal/stats/definition-popover work landed a dozen literal `white`s; see
    design.md §21 for the semantic-variable pass that actually happened.
16. **[Done, 2026-09-01]** ~~Accessibility of status color-coding.~~ Each
    status now has its own `border-bottom-style` too, not just a hue — see
    design.md §21. Status-picker buttons already showed their status name as
    real text and never suppressed the focus ring, so that half of this item
    needed no change.
17. **[Done, 2026-09-01]** ~~A stats view.~~ `/stats` — known/learning/new/ignored
    counts, split between the top 10k and everything else read, since Slice 4
    (SRS) was rejected outright rather than landing here (design.md §19).

---

## Suggested order if picking a few to do first

**Since this was written:** the reader page (#1–7) and the library page (#8–12) are
both fully built (design.md §§13–15). What's left from this original list:

1. ~~Dark mode (#15)~~ **[Done, 2026-09-01]** — design.md §21.
2. ~~Accessibility of status color-coding (#16)~~ **[Done, 2026-09-01]** —
   design.md §21. Status-picker button labeling/keyboard reachability checked
   too and found already fine, no change needed there.
3. ~~A stats view (#17)~~ **[Done, 2026-09-01]** — see #17 above.
4. Visual consistency between the library and vocabulary pages (#6 in the
   cross-cutting section) — explicitly deferred by you earlier, still open.
