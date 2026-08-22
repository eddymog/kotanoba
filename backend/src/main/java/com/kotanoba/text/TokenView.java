package com.kotanoba.text;

/**
 * One token as returned to the reader: the text_token row plus this user's
 * status for its lemma, already merged. status is null for non-word tokens
 * (punctuation, whitespace) — they never had a lemma_id to look a status up
 * against in the first place, so there's nothing to default to NEW.
 */
public record TokenView(
    int position,
    int charStart,
    int charEnd,
    String surfaceText,
    String reading,
    Long lemmaId,
    boolean isWord,
    String status
) {}
