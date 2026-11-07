package com.caseintake.duplicates;

/** An accepted pair of cases from two different documents. */
public record CasePair(String docA, int caseA, String docB, int caseB, int agreements, String confidence,
                       String reason) {}
