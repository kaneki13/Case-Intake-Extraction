package com.caseintake.duplicates;

import java.util.List;

public record DuplicateGroup(List<String> documentIds, String confidence, String reason) {}
