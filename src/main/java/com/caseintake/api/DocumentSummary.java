package com.caseintake.api;

import java.util.List;
import java.util.Map;

public record DocumentSummary(String id, String title, int caseCount, List<String> flags, ReviewCounts reviewCounts) {

    /** Counts over all cases; byConfidence has an entry for every catalog level, in catalog order. */
    public record ReviewCounts(int conflict, int unknown, Map<String, Integer> byConfidence) {}
}
