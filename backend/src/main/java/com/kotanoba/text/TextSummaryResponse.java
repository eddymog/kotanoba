package com.kotanoba.text;

import java.time.Instant;

public record TextSummaryResponse(
    Long id,
    String title,
    int tokenCount,
    int distinctLemmaCount,
    Instant createdAt,
    Instant lastOpenedAt,
    Double difficultyScore
) {

    /**
     * Import response only. difficultyScore is null here, not a placeholder
     * value — the import endpoint doesn't need it, so it's genuinely not
     * computed for this response. The library list (TextLibraryRepository)
     * always populates a real one. lastOpenedAt is genuinely null too: a
     * just-imported text hasn't been opened yet (design.md §14 — opening,
     * not importing, is what sets it).
     */
    static TextSummaryResponse from(TextDocument text) {
        return new TextSummaryResponse(
            text.getId(),
            text.getTitle(),
            text.getTokenCount(),
            text.getLemmaIds().size(),
            text.getCreatedAt(),
            null,
            null
        );
    }
}
