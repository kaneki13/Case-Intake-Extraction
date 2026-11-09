package com.caseintake.api.dto;

import java.util.List;

/** The body of GET /api/duplicates: the detector's integer tolerance and the groups. */
public record DuplicatesView(int integerTolerance, List<DuplicateGroupView> groups) {}
