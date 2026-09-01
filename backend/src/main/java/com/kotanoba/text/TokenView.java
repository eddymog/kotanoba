package com.kotanoba.text;

import java.util.List;

/**
 * One token as returned to the reader: the text_token row plus this user's
 * status for its lemma, already merged, plus senses/part-of-speech/example
 * sentence from dictionary_entry/word_frequency/word_example when the lemma
 * matches (design.md §13/§18's JMdict+Tatoeba match, reused here — see
 * TextReadRepository). status, senses, partOfSpeech, and the example fields
 * are all null for non-word tokens (punctuation, whitespace) — they never
 * had a lemma_id to look anything up against.
 */
public record TokenView(
    int position,
    int charStart,
    int charEnd,
    String surfaceText,
    String reading,
    Long lemmaId,
    boolean isWord,
    String status,
    List<String> senses,
    String partOfSpeech,
    String exampleJapanese,
    String exampleEnglish
) {}
