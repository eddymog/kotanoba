package com.kotanoba.lemma;

import java.util.List;

public record VocabularyPageResponse(int page, int totalPages, List<VocabularyWordResponse> words) {}
