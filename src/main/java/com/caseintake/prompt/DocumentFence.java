package com.caseintake.prompt;

import com.caseintake.text.SourceText;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Wraps a document between marker lines so the model can tell data from instructions (LLD 7.1). */
public final class DocumentFence {
    private static final Pattern MARKER = Pattern.compile("[0-9a-f]{16}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private DocumentFence() {}

    /** 16 lower-case hex characters, new for every request. */
    public static String newMarker() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String open(String marker) {
        return "<<<DOCUMENT " + checked(marker) + ">>>";
    }

    public static String close(String marker) {
        return "<<<END DOCUMENT " + checked(marker) + ">>>";
    }

    public static String wrap(SourceText text, String marker) {
        String raw = text.substring(0, text.length());
        if (raw.contains(close(marker))) {
            throw new IllegalArgumentException("document " + text.id() + " contains the closing fence for marker " + marker);
        }
        return open(marker) + "\n" + raw + (raw.endsWith("\n") ? "" : "\n") + close(marker);
    }

    private static String checked(String marker) {
        if (marker == null || !MARKER.matcher(marker).matches()) {
            throw new IllegalArgumentException("fence marker must be 16 lower-case hex characters, got '" + marker + "'");
        }
        return marker;
    }
}
