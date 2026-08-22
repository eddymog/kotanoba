package com.kotanoba.nlp;

import com.kotanoba.nlp.client.generated.api.DefaultApi;
import com.kotanoba.nlp.client.generated.invoker.ApiClient;
import java.time.Duration;
import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

// NlpServiceProperties is picked up by @ConfigurationPropertiesScan on
// KotanobaApplication.
@Configuration
public class NlpClientConfig {

    /**
     * The generated model classes represent Pydantic's Optional[...] fields as
     * JsonNullable<T> (distinguishing "absent" from "null"). Without this
     * module registered, Jackson has no idea how to construct a JsonNullable
     * and throws InvalidDefinitionException the first time a response
     * contains one — which is every response, since every word-level field
     * (normalized_form, reading, ...) is Optional. Spring Boot's Jackson
     * autoconfiguration registers any Module bean onto the shared
     * ObjectMapper automatically, so this fixes both this RestTemplate and
     * (harmlessly) the app's own inbound/outbound JSON handling.
     */
    @Bean
    public JsonNullableModule jsonNullableModule() {
        return new JsonNullableModule();
    }

    @Bean
    public DefaultApi nlpDefaultApi(NlpServiceProperties properties, RestTemplateBuilder builder) {
        // Connect+read timeout here IS the timeout budget for this call — see
        // application.yml's comment on why this isn't a Resilience4j
        // @TimeLimiter (that annotation needs a CompletionStage-returning
        // method; this call is synchronous per decision #4).
        //
        // Explicit SimpleClientHttpRequestFactory (java.net.HttpURLConnection),
        // not Spring Boot 3.4+'s auto-detected JdkClientHttpRequestFactory
        // (java.net.http.HttpClient). Verified with a real request against the
        // real NLP service: HttpClient defaults to attempting HTTP/2, and
        // Uvicorn (h11, HTTP/1.1-only, no TLS/ALPN here) rejects that
        // negotiation outright — "400 Bad Request: Invalid HTTP request
        // received," not a normal FastAPI validation error. A raw curl with
        // identical headers/body succeeds, confirming it's protocol
        // negotiation, not the payload. SimpleClientHttpRequestFactory never
        // attempts anything but HTTP/1.1.
        RestTemplateBuilder configuredBuilder = builder
            .requestFactory(SimpleClientHttpRequestFactory::new)
            .connectTimeout(Duration.ofMillis(properties.timeoutMs()))
            .readTimeout(Duration.ofMillis(properties.timeoutMs()));

        // Empty locally/Compose (no network isolation to substitute for
        // there, but nothing untrusted is on that Docker network either) —
        // set only when deployed, where the NLP service's URL is genuinely
        // public. Must match nlp/app/main.py's INTERNAL_API_KEY exactly.
        if (!properties.apiKey().isBlank()) {
            configuredBuilder = configuredBuilder.defaultHeader("X-Internal-Api-Key", properties.apiKey());
        }

        RestTemplate restTemplate = configuredBuilder.build();

        ApiClient apiClient = new ApiClient(restTemplate);
        apiClient.setBasePath(properties.baseUrl());
        return new DefaultApi(apiClient);
    }
}
