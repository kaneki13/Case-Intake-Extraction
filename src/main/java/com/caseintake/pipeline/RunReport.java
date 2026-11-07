package com.caseintake.pipeline;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a run. {@code remaining} lists the documents not finished because the run was stopped (daily quota);
 * it is empty for a run that went through every selected document.
 */
public record RunReport(List<String> written, Map<String, String> failed, List<String> remaining) {
    public RunReport {
        written = List.copyOf(written);
        failed = new LinkedHashMap<>(failed);
        remaining = List.copyOf(remaining);
    }

    public RunReport(List<String> written, Map<String, String> failed) {
        this(written, failed, List.of());
    }

    /** LLD 1.1: 0 when every document was written, 1 when any failed or was not reached. */
    public int exitCode() {
        return failed.isEmpty() && remaining.isEmpty() ? 0 : 1;
    }

    /** Prints the report; {@code resumeCommand} prefixes the {@code --only} hint for a stopped run. */
    public void print(PrintStream err, String resumeCommand) {
        err.println("Run report: " + written.size() + " written, " + failed.size() + " failed");
        if (!written.isEmpty()) {
            err.println("  written: " + String.join(", ", written));
        }
        failed.forEach((id, reason) -> err.println("  failed: " + id + ": " + reason));
        if (!remaining.isEmpty()) {
            err.println("Run stopped before finishing. Resume with: " + resumeCommand + " --only="
                    + String.join(",", remaining));
        }
    }
}
