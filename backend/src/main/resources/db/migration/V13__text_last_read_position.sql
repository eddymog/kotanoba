-- Real resume-to-exact-spot, the option deliberately scoped OUT of V12's
-- last_opened_at (design.md §14 named this as a follow-up, not closed off).
-- Position is a text_token.position value — nullable until the reader
-- actually saves one, and only ever set on navigating away (design.md §15),
-- not on every click, to keep write volume trivial.
ALTER TABLE text ADD COLUMN last_read_position INT;
