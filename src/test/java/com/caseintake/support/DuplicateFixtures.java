package com.caseintake.support;

import com.caseintake.duplicates.DuplicateProperties;
import java.util.List;

/** The duplicate settings of application.yml, for tests that build commands by hand. */
public final class DuplicateFixtures {
    private DuplicateFixtures() {}

    public static DuplicateProperties standard() {
        return new DuplicateProperties("patient", List.of("patient", "drug", "event"), 5, 8, 1);
    }
}
