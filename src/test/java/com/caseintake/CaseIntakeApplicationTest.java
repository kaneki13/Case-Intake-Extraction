package com.caseintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseintake.CaseIntakeApplication.RunMode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CaseIntakeApplicationTest {
    @Test
    void parsesCommandsAndServerArguments() {
        assertThat(RunMode.parse(new String[0])).isEqualTo(RunMode.SERVER);
        assertThat(RunMode.parse(new String[] {"--server.port=9090"})).isEqualTo(RunMode.SERVER);
        assertThat(RunMode.parse(new String[] {"extract", "--only=D01"})).isEqualTo(RunMode.EXTRACT);
        assertThat(RunMode.parse(new String[] {"REPLAY"})).isEqualTo(RunMode.REPLAY);
    }

    @Test
    void unknownCommandIsRejected() {
        assertThatThrownBy(() -> RunMode.parse(new String[] {"extrct"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown command 'extrct'");
    }

    @Test
    void unknownCommandPrintsUsageAndExitsTwo() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = CaseIntakeApplication.execute(new String[] {"serve"});
        } finally {
            System.setErr(original);
        }
        assertThat(code).isEqualTo(2);
        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .contains("Unknown command 'serve'")
                .contains("Usage: java -jar target/case-intake.jar [extract | replay] [options]");
    }
}
