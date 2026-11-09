package com.caseintake.review;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A field as the API returns it after a review: {@code model} is the out/ field result unchanged (null when the
 * output has no entry for the field) and {@code review} is null when the field is unreviewed.
 */
public record MergedField(String fieldId, JsonNode model, FieldReview review) {}
