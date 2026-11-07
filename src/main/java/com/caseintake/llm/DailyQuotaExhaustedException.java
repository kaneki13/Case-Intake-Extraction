package com.caseintake.llm;

/** Thrown by the retry policy (Task 8) when the per-day quota is spent; never retried. */
public class DailyQuotaExhaustedException extends LlmException {
    public DailyQuotaExhaustedException(String message) {
        super(message);
    }
}
