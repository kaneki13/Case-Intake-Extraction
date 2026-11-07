package com.caseintake.api;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code extraction} is the out/&lt;id&gt;.json content, unchanged. {@code text} is null when there is no .txt. */
public record DocumentDetail(String id, String title, String text, JsonNode extraction) {}
