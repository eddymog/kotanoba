package com.kotanoba.lemma;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SetVocabularyStatusRequest(@NotBlank String term, @NotBlank String reading, @NotNull LemmaStatus status) {}
