package com.caseintake.api;

import com.caseintake.review.FieldReview;
import com.caseintake.review.Progress;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * {@code extraction} is the out/&lt;id&gt;.json content, unchanged. {@code text} is null when there is no .txt.
 * {@code reviews} maps case index → field id → review, holding only reviews for existing cases and catalog fields.
 */
public record DocumentDetail(String id, String title, String text, JsonNode extraction,
                             Map<Integer, Map<String, FieldReview>> reviews, Progress progress) {}
