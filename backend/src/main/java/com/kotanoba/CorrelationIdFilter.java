package com.kotanoba;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Puts a correlation id on every request into MDC (so it shows up in every log
 * line via the pattern in application.yml) and propagates it into the outbound
 * NLP call (see NlpTokenizerClient) — so a single import can be traced across
 * both runtimes, per claude.md's engineering standards.
 *
 * <p>Root-level, not inside any of the user/lemma/text/nlp modules — this is
 * cross-cutting infrastructure, not domain logic belonging to one of them.
 */
@Component
public class CorrelationIdFilter extends HttpFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
