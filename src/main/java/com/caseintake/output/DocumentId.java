package com.caseintake.output;

import java.util.regex.Pattern;

/**
 * The one rule for a document id. An id names the input file's basename, the {@code out/<id>.json} file and the
 * {@code fixtures/<id>/} directory, and the API takes it from the URL, so every place that accepts an id checks it
 * against this pattern.
 */
public final class DocumentId {
    public static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    private DocumentId() {}

    public static boolean isValid(String id) {
        return id != null && PATTERN.matcher(id).matches();
    }
}
