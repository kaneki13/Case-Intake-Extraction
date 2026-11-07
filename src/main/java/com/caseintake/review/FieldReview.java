package com.caseintake.review;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

/**
 * One reviewer decision. {@code value} is present exactly when the state is {@code edited}; {@code at} is an
 * ISO-8601 UTC instant.
 */
public record FieldReview(
        ReviewState state,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode value,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant at) {

    public FieldReview {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(at, "at");
        boolean hasValue = value != null && !value.isNull() && !value.isMissingNode();
        if (hasValue != (state == ReviewState.EDITED)) {
            throw new IllegalArgumentException("a value is required for edited and forbidden otherwise");
        }
    }
}
