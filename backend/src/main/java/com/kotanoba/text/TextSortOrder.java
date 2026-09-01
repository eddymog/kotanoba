package com.kotanoba.text;

/**
 * Whitelisted sort keys for the library listing — never build ORDER BY from
 * a raw request string directly, even a validated one; switch on this enum
 * instead (TextLibraryRepository).
 */
public enum TextSortOrder {
    DIFFICULTY,
    RECENT
}
