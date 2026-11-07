package com.caseintake.duplicates;

import java.util.List;

public record DuplicateReport(List<DuplicateGroup> groups, List<CasePair> pairs) {}
