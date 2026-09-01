package com.kotanoba.text;

import jakarta.validation.constraints.NotBlank;

public record UpdateTextTitleRequest(@NotBlank String title) {}
