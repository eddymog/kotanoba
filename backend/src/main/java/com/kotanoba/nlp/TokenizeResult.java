package com.kotanoba.nlp;

import java.util.List;

public record TokenizeResult(List<TokenizedWord> tokens, int tokenCount) {}
