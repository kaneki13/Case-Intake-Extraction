package com.caseintake.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record LlmRequest(String model, String systemInstruction, String userContent, JsonNode responseJsonSchema,
        List<LlmAttachment> attachments) {}
