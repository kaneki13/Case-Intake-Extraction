package com.caseintake.llm;

import java.util.Set;

/**
 * The secret the active {@link LlmClient} sends with each call, supplied by the adapter so that callers stay
 * provider-neutral.
 *
 * @param secret the configured value; may be null or blank when it is not set (replay and server run without it)
 * @param settingName where the secret is configured, for error messages, e.g. an environment variable name
 * @param headerNames the request header names that carry the secret; recorded exchanges drop any JSON field with
 *     one of these names
 */
public record LlmCredential(String secret, String settingName, Set<String> headerNames) {
    public LlmCredential {
        headerNames = Set.copyOf(headerNames);
    }

    public boolean isPresent() {
        return secret != null && !secret.isBlank();
    }

    /** Never prints the secret. */
    @Override
    public String toString() {
        return "LlmCredential[settingName=" + settingName + ", secret=" + (isPresent() ? "***" : "<empty>")
                + ", headerNames=" + headerNames + "]";
    }
}
