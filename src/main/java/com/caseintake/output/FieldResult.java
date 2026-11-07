package com.caseintake.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One field of one case. {@code provenance} is omitted from the JSON when empty: the output schema requires at least
 * one item whenever the key is present, and an unknown field has no quotes.
 */
public record FieldResult(
        FieldStatus status, JsonNode value, String confidence,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Provenance> provenance, String note) {}
