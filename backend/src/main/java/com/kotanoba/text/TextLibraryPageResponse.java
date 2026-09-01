package com.kotanoba.text;

import java.util.List;

public record TextLibraryPageResponse(int page, int totalPages, List<TextSummaryResponse> texts) {}
