package com.caseintake;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Guards against a Google API key landing in any file git could commit. Reports paths only, never content. */
class NoSecretsCommittedTest {

    private static final Pattern GOOGLE_API_KEY = Pattern.compile("AIza[0-9A-Za-z_\\-]{35}");

    @Test
    void noGoogleApiKeyInCommittableFiles() throws Exception {
        Process p = new ProcessBuilder("git", "ls-files", "-co", "--exclude-standard", "-z").start();
        byte[] raw = p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).as("git ls-files exit code").isZero();

        List<String> offenders = new ArrayList<>();
        for (String name : new String(raw, StandardCharsets.UTF_8).split("\0")) {
            if (name.isEmpty()) {
                continue;
            }
            Path file = Path.of(name);
            if (!Files.isRegularFile(file)) {
                continue; // deleted but still tracked
            }
            String text = readTextOrNull(file);
            if (text != null && GOOGLE_API_KEY.matcher(text).find()) {
                offenders.add(name);
            }
        }
        assertThat(offenders).as("files containing a Google API key (paths only)").isEmpty();
    }

    @Test
    void gitignoreCoversKeyFolder() throws Exception {
        Process p = new ProcessBuilder("git", "check-ignore", "-q", "Gemini API Key/x").start();
        assertThat(p.waitFor()).as("git check-ignore exit code").isZero();
    }

    @Test
    void gitignoreCoversEnvFileVariants() throws Exception {
        for (String name : new String[] {".env", ".env.local", ".env.production", "frontend/.env.local"}) {
            Process p = new ProcessBuilder("git", "check-ignore", "-q", name).start();
            assertThat(p.waitFor()).as("git check-ignore " + name).isZero();
        }
    }

    /** Returns file text, or null for binary files (e.g. PDFs) that are not valid UTF-8 or contain NUL bytes. */
    private static String readTextOrNull(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        for (byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
