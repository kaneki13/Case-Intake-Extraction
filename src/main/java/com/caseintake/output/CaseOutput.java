package com.caseintake.output;

import java.util.LinkedHashMap;

public record CaseOutput(int caseIndex, String label, LinkedHashMap<String, FieldResult> fields) {}
