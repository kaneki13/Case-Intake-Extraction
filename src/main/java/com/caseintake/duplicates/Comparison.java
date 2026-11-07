package com.caseintake.duplicates;

/** Outcome of comparing one field in two cases. Only extracted values count as present. */
public enum Comparison { MATCH, DIFFER, ONE_SIDED, BOTH_ABSENT }
