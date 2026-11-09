package com.caseintake.review;

import com.fasterxml.jackson.databind.JsonNode;

/** Body of a single-field PATCH: {@code action} is accept, reject, edit or reset; {@code value} only for edit. */
public record ReviewRequest(String action, JsonNode value) {}
