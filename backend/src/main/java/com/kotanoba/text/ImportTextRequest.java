package com.kotanoba.text;

import jakarta.validation.constraints.NotBlank;

public record ImportTextRequest(String title, @NotBlank String text) {}
