package com.kotanoba.nlp;

/**
 * Domain-level view of one tokenized word — decouples TextImportService from
 * the generated client's model classes, so a regenerated client (contract
 * change) only ever breaks compilation inside this module, never downstream.
 */
public record TokenizedWord(
    String surface,
    int charStart,
    int charEnd,
    boolean isWord,
    String normalizedForm,
    String dictionaryForm,
    String reading,
    String partOfSpeech
) {}
