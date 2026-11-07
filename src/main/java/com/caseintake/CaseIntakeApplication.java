package com.caseintake;

import com.caseintake.pipeline.ExtractCommand;
import com.caseintake.pipeline.ReplayCommand;
import java.util.Locale;
import java.util.function.IntSupplier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CaseIntakeApplication {

    /** Entry mode chosen from {@code args[0]} (LLD 1.1). */
    public enum RunMode {
        EXTRACT,
        REPLAY,
        SERVER;

        /**
         * No arguments, or a first argument that is an option ({@code --...}), starts the server. Otherwise the first
         * argument must name a command; anything else throws IllegalArgumentException.
         */
        public static RunMode parse(String[] args) {
            if (args == null || args.length == 0 || args[0].startsWith("--")) {
                return SERVER;
            }
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "extract" -> EXTRACT;
                case "replay" -> REPLAY;
                default -> throw new IllegalArgumentException("Unknown command '" + args[0] + "'");
            };
        }

        public boolean isCommand() {
            return this != SERVER;
        }
    }

    static final String USAGE = String.join(System.lineSeparator(),
            "Usage: java -jar target/case-intake.jar [extract | replay] [options]",
            "  (no command)  start the review server",
            "  extract       call the model and record fixtures: [--only=<id>,...] [--stage=cases|extract] [--force]",
            "  replay        rebuild outputs from fixtures: [--only=<id>,...] [--out=<dir>]");

    public static void main(String[] args) {
        RunMode mode;
        try {
            mode = RunMode.parse(args);
        } catch (IllegalArgumentException e) {
            System.exit(usage(e.getMessage()));
            return;
        }
        if (!mode.isCommand()) {
            new SpringApplication(CaseIntakeApplication.class).run(args);
            return;
        }
        System.exit(execute(args));
    }

    /**
     * Runs the extract or replay command named by {@code args[0]} and returns its exit code (LLD 1.1): 0 all written,
     * 1 a document failed, 2 configuration error. Beans are created lazily, so replay never builds an LlmClient.
     */
    public static int execute(String[] args) {
        RunMode mode;
        try {
            mode = RunMode.parse(args);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (!mode.isCommand()) {
            throw new IllegalArgumentException("execute needs an extract or replay command");
        }
        SpringApplication app = new SpringApplication(CaseIntakeApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLazyInitialization(true);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        ConfigurableApplicationContext ctx = null;
        IntSupplier command;
        try {
            ctx = app.run(args);
            ApplicationArguments arguments = ctx.getBean(ApplicationArguments.class);
            if (mode == RunMode.EXTRACT) {
                ExtractCommand extract = ctx.getBean(ExtractCommand.class);
                command = () -> extract.execute(arguments);
            } else {
                ReplayCommand replay = ctx.getBean(ReplayCommand.class);
                command = () -> replay.execute(arguments);
            }
        } catch (RuntimeException e) {
            System.err.println("Configuration error: " + e.getMessage());
            if (ctx != null) {
                ctx.close();
            }
            return 2;
        }
        int code = command.getAsInt();
        return SpringApplication.exit(ctx, () -> code);
    }

    /** Prints the problem and the usage to stderr; returns exit code 2 (LLD 1.1). */
    private static int usage(String problem) {
        System.err.println(problem);
        System.err.println(USAGE);
        return 2;
    }
}
