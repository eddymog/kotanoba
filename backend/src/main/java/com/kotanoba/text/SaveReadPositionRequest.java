package com.kotanoba.text;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SaveReadPositionRequest(@NotNull @Min(0) Integer position) {}
