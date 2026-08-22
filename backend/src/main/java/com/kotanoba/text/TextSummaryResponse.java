package com.kotanoba.text;

import java.time.Instant;

public record TextSummaryResponse(Long id, String title, int tokenCount, int distinctLemmaCount, Instant createdAt) {

    static TextSummaryResponse from(TextDocument text) {
        return new TextSummaryResponse(
            text.getId(), text.getTitle(), text.getTokenCount(), text.getLemmaIds().size(), text.getCreatedAt()
        );
    }
}
