package com.caseintake.llm;

import com.fasterxml.jackson.databind.JsonNode;

public record LlmRequest(String model, String systemInstruction, String userContent, JsonNode responseJsonSchema) {}
