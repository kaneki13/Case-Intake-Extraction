package com.caseintake.api.dto;

import java.util.List;

/** One duplicate group as the API shows it: the detector's group plus every accepted case pair inside it. */
public record DuplicateGroupView(List<String> documentIds, String confidence, String reason, List<PairView> pairs) {

    public record CaseRef(String id, int caseIndex) {}

    public record PairView(CaseRef a, CaseRef b, int agreements) {}
}
