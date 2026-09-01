package com.kotanoba.lemma;

/**
 * How many words in some set (the top 10k, or the words you've encountered
 * outside it) sit at each status — see VocabularyStatsRepository. total is
 * the sum of the four, included so the frontend doesn't have to re-add them.
 */
public record StatusCounts(int total, int newCount, int learningCount, int knownCount, int ignoredCount) {}
