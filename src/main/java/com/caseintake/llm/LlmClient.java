package com.caseintake.llm;

/** Provider-neutral model access. The pipeline depends only on this interface. */
public interface LlmClient {
    LlmResponse generate(LlmRequest request);

    String providerId();

    /** The secret this client sends and the headers that carry it, so recordings can remove both. */
    LlmCredential credential();
}
