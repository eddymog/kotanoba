package com.kotanoba.nlp;

/**
 * Thrown after retries are exhausted or the circuit breaker is open. The
 * import path (decision #4) has no queue to absorb this in Slice 1, so it
 * must surface as a clean failure the caller can turn into an HTTP error —
 * not a hang.
 */
public class NlpServiceUnavailableException extends RuntimeException {

    public NlpServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
