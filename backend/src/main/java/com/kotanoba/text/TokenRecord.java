package com.kotanoba.text;

/**
 * One row bound for text_token. Deliberately a plain record, not a JPA
 * entity — see TextTokenBatchWriter.
 */
public record TokenRecord(
    int position,
    int charStart,
    int charEnd,
    String surfaceText,
    String reading,
    Long lemmaId,
    boolean isWord
) {}
