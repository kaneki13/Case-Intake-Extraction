package com.caseintake.fixtures;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/** Mirrors the fixture file. {@code outputText} makes replay provider-agnostic. */
@JsonPropertyOrder({"document_id", "stage", "attempt", "provider", "model", "catalog_version", "prompt_sha256",
        "recorded_at", "output_text", "request", "response"})
public record FixtureRecord(
        @JsonProperty("document_id") String documentId,
        @JsonProperty("stage") String stage,
        @JsonProperty("attempt") int attempt,
        @JsonProperty("provider") String provider,
        @JsonProperty("model") String model,
        @JsonProperty("catalog_version") String catalogVersion,
        @JsonProperty("prompt_sha256") String promptSha256,
        @JsonProperty("recorded_at") String recordedAt,
        @JsonProperty("output_text") String outputText,
        @JsonProperty("request") Request request,
        @JsonProperty("response") Response response) {

    @JsonPropertyOrder({"url", "body"})
    public record Request(@JsonProperty("url") String url, @JsonProperty("body") JsonNode body) {}

    @JsonPropertyOrder({"http_status", "body"})
    public record Response(@JsonProperty("http_status") int httpStatus, @JsonProperty("body") JsonNode body) {}

    Exchange toExchange(ExchangeKey key) {
        return new Exchange(key, attempt, provider, model, outputText);
    }
}
