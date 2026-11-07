package com.caseintake.catalog;

import java.util.List;

public record FieldDef(String id, String label, String sectionId, FieldType type,
                       List<OptionDef> options, ValidationRules validation, String guidance) {}
