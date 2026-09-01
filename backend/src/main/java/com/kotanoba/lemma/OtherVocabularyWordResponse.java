package com.kotanoba.lemma;

import java.util.List;

/**
 * A word you've actually encountered (it has a real lemma row from import)
 * that isn't in the top 10k frequency list — see OtherVocabularyRepository.
 * Keyed by lemmaId, not term+reading: unlike the top-10k browse page, this
 * lemma always already exists, so status changes go straight to the
 * existing PUT /api/lemmas/{id}/status, no find-or-create needed. No rank
 * (doesn't apply). senses/exampleJapanese/exampleEnglish come from
 * dictionary_entry/word_example (design.md §18), not word_frequency, which
 * by definition has no row for a word outside the top 10k — they're null
 * only when even that broader JMdict/Tatoeba match misses too.
 */
public record OtherVocabularyWordResponse(
    Long lemmaId,
    String term,
    String reading,
    LemmaStatus status,
    String partOfSpeech,
    List<String> senses,
    String exampleJapanese,
    String exampleEnglish
) {}
