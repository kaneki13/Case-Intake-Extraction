package com.caseintake.text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Document text held as Unicode code points, with a lazily built folded form for quote matching. */
public final class SourceText {
    /** Folded text plus, for each folded position, the original code-point index. */
    record Folded(int[] cps, int[] origIndex) {}

    private final String id;
    private final int[] codePoints;
    private volatile Folded folded;

    private SourceText(String id, int[] codePoints) {
        this.id = id;
        this.codePoints = codePoints;
    }

    public static SourceText of(String id, String raw) {
        return new SourceText(id, raw.codePoints().toArray());
    }

    public static SourceText read(String id, Path path) {
        try {
            return of(id, Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String id() {
        return id;
    }

    /** Length in code points. */
    public int length() {
        return codePoints.length;
    }

    /** Original text between code-point offsets [startCp, endCp). */
    public String substring(int startCp, int endCp) {
        return new String(codePoints, startCp, endCp - startCp);
    }

    Folded folded() {
        Folded f = folded;
        if (f == null) {
            f = fold(codePoints);
            folded = f;
        }
        return f;
    }

    static Folded fold(int[] src) {
        int[] out = new int[src.length];
        int[] orig = new int[src.length];
        int n = 0;
        boolean inSpace = false;
        for (int i = 0; i < src.length; i++) {
            int cp = src[i];
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (!inSpace) {
                    out[n] = ' ';
                    orig[n++] = i;
                    inSpace = true;
                }
                continue;
            }
            inSpace = false;
            out[n] = foldOne(cp);
            orig[n++] = i;
        }
        return new Folded(java.util.Arrays.copyOf(out, n), java.util.Arrays.copyOf(orig, n));
    }

    private static int foldOne(int cp) {
        return switch (cp) {
            case '‘', '’' -> '\'';
            case '“', '”' -> '"';
            case '–', '—' -> '-';
            default -> Character.toLowerCase(cp);
        };
    }
}
