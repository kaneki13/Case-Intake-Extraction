package com.caseintake.cli;

import com.caseintake.pipeline.ExtractCommand;
import com.caseintake.pipeline.ReplayCommand;
import java.util.Locale;
import java.util.function.IntSupplier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Command-line front controller: chooses between the review server, {@code extract} and {@code replay} from the
 * arguments (LLD 1.1) and runs the chosen mode. Kept out of the application class so that class only bootstraps.
 */
public final class CommandLauncher {

    private CommandLauncher() {}

    public enum RunMode {
        EXTRACT,
        REPLAY,
        SERVER;


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
            "  extract       call the model and record fixtures: [--only=<id>,...] [--stage=transcribe|cases|extract] [--force]",
            "  replay        rebuild outputs from fixtures: [--only=<id>,...] [--out=<dir>] [--derived=<dir>]");

    public static void launch(Class<?> source, String[] args) {
        RunMode mode;
        try {
            mode = RunMode.parse(args);
        } catch (IllegalArgumentException e) {
            System.exit(usage(e.getMessage()));
            return;
        }
        if (!mode.isCommand()) {
            new SpringApplication(source).run(args);
            return;
        }
        System.exit(execute(source, args));
    }

    /**
     * Runs the extract or replay command named by {@code args[0]} for the Spring application {@code source} and returns its exit code (LLD 1.1): 0 all written,
     * 1 a document failed, 2 configuration error. Beans are created lazily, so replay never builds an LlmClient.
     */
    public static int execute(Class<?> source, String[] args) {
        RunMode mode;
        try {
            mode = RunMode.parse(args);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        if (!mode.isCommand()) {
            throw new IllegalArgumentException("execute needs an extract or replay command");
        }
        SpringApplication app = new SpringApplication(source);
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

    private static int usage(String problem) {
        System.err.println(problem);
        System.err.println(USAGE);
        return 2;
    }
}
