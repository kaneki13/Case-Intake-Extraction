package com.caseintake.prompt;

/** A model request's system instruction and user message. */
public record Prompt(String system, String user) {}
