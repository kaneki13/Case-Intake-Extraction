package com.caseintake.validation;

public record ValidationResult(boolean ok, String error) {
    private static final ValidationResult OK = new ValidationResult(true, null);

    public static ValidationResult success() {
        return OK;
    }

    public static ValidationResult fail(String error) {
        return new ValidationResult(false, error);
    }
}
