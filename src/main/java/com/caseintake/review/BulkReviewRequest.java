package com.caseintake.review;

import java.util.List;

/**
 * Body of a bulk PATCH: {@code action} is accept or reject. Without {@code fieldIds} it covers every catalog field;
 * either way only unreviewed fields change.
 */
public record BulkReviewRequest(String action, List<String> fieldIds) {}
