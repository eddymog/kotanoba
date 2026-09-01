package com.kotanoba.lemma;

import java.util.List;

/**
 * senses (design.md §18) replaces the old single collapsed meaning string —
 * dictionary_entry keeps every JMdict sense, not just one. exampleJapanese/
 * exampleEnglish are null when word_example has no row for this word.
 */
public record VocabularyWordResponse(
    String term,
    String reading,
    int rank,
    LemmaStatus status,
    String partOfSpeech,
    List<String> senses,
    String exampleJapanese,
    String exampleEnglish
) {}
