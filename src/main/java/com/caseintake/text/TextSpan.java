package com.caseintake.text;

/** Half-open span of Unicode code-point offsets into a {@link SourceText}. */
public record TextSpan(int start, int end) {
    public TextSpan {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid span [" + start + ", " + end + ")");
        }
    }
}
