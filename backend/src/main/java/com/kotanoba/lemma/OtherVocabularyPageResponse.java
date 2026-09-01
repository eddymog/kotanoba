package com.kotanoba.lemma;

import java.util.List;

public record OtherVocabularyPageResponse(int page, int totalPages, List<OtherVocabularyWordResponse> words) {}
