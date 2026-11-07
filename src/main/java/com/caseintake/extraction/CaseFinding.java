package com.caseintake.extraction;

import java.util.EnumSet;
import java.util.List;

/** Result of case finding: verified cases, document hints, document flags and reviewer notes. */
public record CaseFinding(
        List<CaseCandidate> cases, DocumentHints hints, EnumSet<DocumentFlag> flags, List<String> notes) {
    public CaseFinding {
        cases = List.copyOf(cases);
        flags = EnumSet.copyOf(flags);
        notes = List.copyOf(notes);
    }
}
