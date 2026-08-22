package com.kotanoba.lemma;

import jakarta.validation.constraints.NotNull;

public record SetLemmaStatusRequest(@NotNull LemmaStatus status) {}
