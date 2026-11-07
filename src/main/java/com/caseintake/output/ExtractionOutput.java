package com.caseintake.output;

import java.util.List;

public record ExtractionOutput(
        String documentId, String catalogVersion, String model, List<String> flags, List<CaseOutput> cases) {}
