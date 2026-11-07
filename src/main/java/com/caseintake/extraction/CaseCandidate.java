package com.caseintake.extraction;

import com.caseintake.text.TextSpan;
import java.util.Map;

/**
 * One patient found by case finding: a short label, the located span that identifies the patient,
 * and the located span of each minimum element.
 */
public record CaseCandidate(String label, TextSpan anchor, Map<MinimumElement, TextSpan> elements) {
    public CaseCandidate {
        elements = Map.copyOf(elements);
    }
}
