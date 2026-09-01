package com.kotanoba.lemma;

/**
 * design.md §19: the same NEW/LEARNING/KNOWN/IGNORED breakdown the two
 * vocabulary lists already filter by (§13, §17), aggregated instead of
 * listed — topWords covers the fixed top 10k frequency list,
 * otherWords covers everything you've actually encountered outside it.
 */
public record VocabularyStatsResponse(StatusCounts topWords, StatusCounts otherWords) {}
