package com.caseintake.catalog;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

public record ValidationRules(Optional<Pattern> pattern, Optional<BigDecimal> min, Optional<BigDecimal> max) {
    public static final ValidationRules NONE = new ValidationRules(Optional.empty(), Optional.empty(), Optional.empty());
}
