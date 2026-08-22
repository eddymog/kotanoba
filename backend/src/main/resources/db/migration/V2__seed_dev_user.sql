-- Temporary stand-in until real auth (Spring Security, JWT, Argon2) lands —
-- see design.md progress checklist. Every write path currently hardcodes this
-- as the acting user (see com.kotanoba.user.CurrentUser). Remove this
-- migration, and CurrentUser's hardcoded id, in the same change that adds
-- real authentication.
INSERT INTO app_user (email, password_hash)
VALUES ('dev@kotanoba.local', 'unusable-placeholder-not-a-real-hash');
