package se306.scheduler.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import se306.scheduler.io.DotOutputWriter;

/**
 * Parsed and validated command line options.
 *
 * <pre>
 * java -jar scheduler.jar INPUT.dot P [OPTION]...
 *   INPUT.dot   task graph to schedule
 *   P           number of processors to schedule onto
 *   -p N        use N cores for execution in parallel (default 1)
 *   -v          visualise the search
 *   -o OUTPUT   output file name (default INPUT-output.dot)
 * </pre>
 */
public final class CommandLineArgs {

    private final Path inputFile;
    private final int processorCount;
    private final int coreCount;
    private final boolean visualise;
    private final Path outputFile;

    private CommandLineArgs(Path inputFile, int processorCount, int coreCount, boolean visualise,
                            Path outputFile) {
        this.inputFile = inputFile;
        this.processorCount = processorCount;
        this.coreCount = coreCount;
        this.visualise = visualise;
        this.outputFile = outputFile;
    }

    public Path inputFile() {
        return inputFile;
    }

    /** Number of processors the tasks must be scheduled onto. */
    public int processorCount() {
        return processorCount;
    }

    /** Number of cores the search itself may use ({@code -p}). */
    public int coreCount() {
        return coreCount;
    }

    /** Whether to show the search visualisation ({@code -v}). */
    public boolean visualise() {
        return visualise;
    }

    public Path outputFile() {
        return outputFile;
    }

    /**
     * Parses and validates arguments.
     *
     * @throws IllegalArgumentException with a user-facing message if anything is wrong
     */
    public static CommandLineArgs parse(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("expected at least an input file and a processor "
                    + "count.\n\n" + usage());
        }

        Path input = Path.of(args[0]);
        if (!args[0].toLowerCase(Locale.ROOT).endsWith(".dot")) {
            throw new IllegalArgumentException("input file '" + args[0] + "' is not a .dot file.");
        }
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("input file '" + args[0] + "' does not exist.");
        }
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("input file '" + args[0] + "' is not a regular file.");
        }
        if (!Files.isReadable(input)) {
            throw new IllegalArgumentException("input file '" + args[0] + "' is not readable.");
        }

        int processors = positiveInt(args[1], "processor count");

        int cores = 1;
        boolean visualise = false;
        Path output = null;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "-p" -> {
                    cores = positiveInt(requireValue(args, ++i, "-p"), "-p core count");
                }
                case "-v" -> visualise = true;
                case "-o" -> output = Path.of(requireValue(args, ++i, "-o"));
                default -> throw new IllegalArgumentException(
                        "unrecognised option '" + args[i] + "'.\n\n" + usage());
            }
        }

        if (output == null) {
            output = DotOutputWriter.defaultOutputPath(input);
        }
        return new CommandLineArgs(input, processors, cores, visualise, output);
    }

    private static String requireValue(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException("option '" + option + "' needs a value.");
        }
        return args[i];
    }

    private static int positiveInt(String raw, String what) {
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a whole number, got '" + raw + "'.");
        }
        if (value < 1) {
            throw new IllegalArgumentException(what + " must be at least 1, got " + value + ".");
        }
        return value;
    }

    public static String usage() {
        return """
               Usage: java -jar scheduler.jar INPUT.dot P [OPTION]...

                 INPUT.dot   task graph to schedule, in DOT format
                 P           number of processors to schedule onto

               Options:
                 -p N        use N cores for execution in parallel (default 1)
                 -v          visualise the search
                 -o OUTPUT   output file name (default INPUT-output.dot)""";
    }
}