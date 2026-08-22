package com.kotanoba.nlp;

import com.kotanoba.nlp.client.generated.api.DefaultApi;
import com.kotanoba.nlp.client.generated.model.SplitMode;
import com.kotanoba.nlp.client.generated.model.Token;
import com.kotanoba.nlp.client.generated.model.TokenizeRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The only thing in the codebase that calls the NLP service. Synchronous per
 * decision #4 — the connect/read timeout on the underlying RestTemplate
 * (NlpClientConfig) bounds how long a single attempt can take, Retry governs
 * how many attempts, and CircuitBreaker stops hammering a service that's
 * already down.
 */
@Component
public class NlpTokenizerClient {

    private final DefaultApi api;

    public NlpTokenizerClient(DefaultApi api) {
        this.api = api;
    }

    @Retry(name = "nlpService")
    @CircuitBreaker(name = "nlpService", fallbackMethod = "fail")
    public TokenizeResult tokenize(String text) {
        TokenizeRequest request = new TokenizeRequest();
        request.setText(text);
        request.setMode(SplitMode.C); // decision #1: coarse granularity is the default

        String correlationId = MDC.get(com.kotanoba.CorrelationIdFilter.MDC_KEY);
        var response = api.tokenize(request, correlationId);

        List<TokenizedWord> tokens = response.getTokens().stream()
            .map(NlpTokenizerClient::toDomain)
            .toList();

        return new TokenizeResult(tokens, response.getTokenCount());
    }

    private static TokenizedWord toDomain(Token t) {
        return new TokenizedWord(
            t.getSurface(),
            t.getCharStart(),
            t.getCharEnd(),
            Boolean.TRUE.equals(t.getIsWord()),
            t.getNormalizedForm(),
            t.getDictionaryForm(),
            t.getReading(),
            t.getPartOfSpeech()
        );
    }

    // Resilience4j fallback signature: same params as the guarded method, plus
    // the exception that triggered it — Exception covers everything Retry
    // exhaustion or an open circuit breaker can hand this.
    @SuppressWarnings("unused")
    private TokenizeResult fail(String text, Exception exception) {
        throw new NlpServiceUnavailableException(
            "NLP service unavailable after retries/circuit breaker", exception
        );
    }
}
