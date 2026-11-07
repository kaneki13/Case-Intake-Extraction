package com.caseintake.fixtures;

public record Exchange(ExchangeKey key, int attempt, String provider, String model, String outputText) {}
