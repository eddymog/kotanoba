package com.kotanoba.nlp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nlp-service")
public record NlpServiceProperties(String baseUrl, int timeoutMs, String apiKey) {}
