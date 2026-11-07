package com.caseintake.extraction;

/** Document-wide hints from case finding. The convention counts only when its evidence quote was located. */
public record DocumentHints(DateConvention dateConvention, boolean evidenceLocated) {}
