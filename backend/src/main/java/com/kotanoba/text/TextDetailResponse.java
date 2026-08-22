package com.kotanoba.text;

import java.time.Instant;
import java.util.List;

public record TextDetailResponse(Long id, String title, Instant createdAt, List<TokenView> tokens) {}
