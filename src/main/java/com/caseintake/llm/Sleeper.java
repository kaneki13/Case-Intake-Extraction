package com.caseintake.llm;

import java.time.Duration;

/** Seam over blocking waits so rate limiting and retry are testable without real time. */
public interface Sleeper {
    void sleep(Duration d);

    /** Production implementation: {@link Thread#sleep}. */
    static Sleeper system() {
        return d -> {
            try {
                Thread.sleep(d.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("interrupted while waiting", e);
            }
        };
    }
}
