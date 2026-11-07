package com.caseintake.text;

import java.util.Optional;
import java.util.OptionalInt;

/** Finds a model-supplied quote in the source text and returns its original code-point span. */
public final class QuoteLocator {
    private static final int MIN_QUOTE_CP = 3;

    private QuoteLocator() {}

    public static Optional<TextSpan> locate(SourceText text, String quote, OptionalInt nearCp) {
        if (quote == null) return Optional.empty();
        int[] q = trim(SourceText.fold(quote.codePoints().toArray()).cps());
        if (checkerLength(q) < MIN_QUOTE_CP) return Optional.empty();

        SourceText.Folded f = text.folded();
        int[] hay = f.cps();
        int best = -1;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i + q.length <= hay.length; i++) {
            if (!matchesAt(hay, i, q)) continue;
            if (nearCp.isEmpty()) {
                best = i;
                break;
            }
            long dist = Math.abs((long) f.origIndex()[i] - nearCp.getAsInt());
            if (dist < bestDist) { // strict: ties keep the earlier occurrence
                bestDist = dist;
                best = i;
            }
        }
        if (best < 0) return Optional.empty();
        int start = f.origIndex()[best];
        int end = f.origIndex()[best + q.length - 1] + 1;
        int[] spanFolded = trim(SourceText.fold(text.substring(start, end).codePoints().toArray()).cps());
        if (checkerLength(spanFolded) < MIN_QUOTE_CP) return Optional.empty();
        return Optional.of(new TextSpan(start, end));
    }

    /** Length after the checker's norm: folded, trimmed, trailing '.' stripped. Gate only; never used for search. */
    private static int checkerLength(int[] folded) {
        int n = folded.length;
        while (n > 0 && folded[n - 1] == '.') n--;
        return n;
    }

    private static boolean matchesAt(int[] hay, int at, int[] q) {
        for (int j = 0; j < q.length; j++) {
            if (hay[at + j] != q[j]) return false;
        }
        return true;
    }

    private static int[] trim(int[] a) {
        int s = 0, e = a.length;
        while (s < e && a[s] == ' ') s++;
        while (e > s && a[e - 1] == ' ') e--;
        return java.util.Arrays.copyOfRange(a, s, e);
    }
}
