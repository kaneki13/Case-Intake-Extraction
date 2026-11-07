package com.caseintake.llm;

/** {@code requestUrl} never contains the API key. */
public record LlmResponse(String model, int httpStatus, String rawRequestBody, String rawResponseBody,
                          String requestUrl, String outputText) {}
