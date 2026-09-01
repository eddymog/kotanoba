-- Adds normalized_form to word_frequency so it can finally be joined against
-- lemma on the same identity key lemma already uses everywhere else
-- (design.md §16). Filled by V15's batch, deduped by V16.
ALTER TABLE word_frequency ADD COLUMN normalized_form TEXT;
