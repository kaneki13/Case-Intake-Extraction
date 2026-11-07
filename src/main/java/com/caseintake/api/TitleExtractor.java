package com.caseintake.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Derives a display title from a document's text. */
public final class TitleExtractor {
    static final String NO_TEXT = "(no text layer)";
    private static final int MAX = 120;
    private static final Pattern SUBJECT = Pattern.compile("^subject:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private TitleExtractor() {}

    /** The first Subject line, else the first non-blank line, trimmed and cut to 120 characters. */
    public static String title(String text) {
        if (text == null) {
            return NO_TEXT;
        }
        String firstNonBlank = null;
        for (String line : text.split("\\R")) {
            Matcher m = SUBJECT.matcher(line);
            if (m.matches() && !m.group(1).isBlank()) {
                return cut(m.group(1));
            }
            if (firstNonBlank == null && !line.isBlank()) {
                firstNonBlank = line;
            }
        }
        return firstNonBlank == null ? NO_TEXT : cut(firstNonBlank);
    }

    private static String cut(String s) {
        String t = s.trim();
        return t.length() <= MAX ? t : t.substring(0, MAX);
    }
}
