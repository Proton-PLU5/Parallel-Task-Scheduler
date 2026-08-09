package se306.scheduler.cli;

import java.nio.file.Path;

/**
 * The parsed command line: {@code java -jar scheduler.jar INPUT.dot P [-p N] [-v] [-o OUTPUT]}.
 *
 * <p>This only captures and validates the arguments — nothing here runs a scheduler or writes a
 * file. The stages that will ({@code -p} parallel search, {@code -v} visualisation, {@code -o} the
 * DOT writer) read their settings from this record when they land.
 *
 * @param inputFile      the task graph to read, in DOT format
 * @param processorCount P — number of processors to schedule the graph on
 * @param coreCount      N from {@code -p N}; 1 (sequential) when the flag is absent
 * @param visualise      whether {@code -v} was given
 * @param outputFile     the file the schedule will be written to; defaults to
 *                       {@code INPUT-output.dot} next to the input, and always ends in
 *                       {@code .dot}, so {@code -o output} writes {@code output.dot}
 */
public record CliArguments(Path inputFile, int processorCount, int coreCount, boolean visualise,
        Path outputFile) {

    private static final String DOT_SUFFIX = ".dot";

    /**
     * Parses {@code args} as given to {@code main}.
     *
     * @throws CliArgumentException if the arguments don't match the usage
     */
    public static CliArguments parse(String[] args) {
        if (args.length < 2) {
            throw new CliArgumentException("expected an input file and a processor count");
        }

        Path inputFile = Path.of(args[0]);
        int processorCount = parsePositiveInt(args[1], "P (number of processors)");

        int coreCount = 1;
        boolean visualise = false;
        Path outputFile = null;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "-p" -> coreCount =
                        parsePositiveInt(optionValue(args, ++i, "-p"), "N (number of cores)");
                case "-v" -> visualise = true;
                case "-o" -> outputFile = outputFile(optionValue(args, ++i, "-o"));
                default -> throw new CliArgumentException("unknown option '" + args[i] + "'");
            }
        }

        if (outputFile == null) {
            outputFile = defaultOutputFile(inputFile);
        }
        return new CliArguments(inputFile, processorCount, coreCount, visualise, outputFile);
    }

    /**
     * The {@code OUTPUT} of {@code -o OUTPUT} as a path. The written file is always DOT, so a name
     * given without the extension gets one: {@code -o output} and {@code -o output.dot} both write
     * {@code output.dot}.
     */
    private static Path outputFile(String output) {
        return Path.of(endsWithDot(output) ? output : output + DOT_SUFFIX);
    }

    /** {@code graphs/example.dot} becomes {@code graphs/example-output.dot}. */
    private static Path defaultOutputFile(Path inputFile) {
        String name = inputFile.getFileName().toString();
        if (endsWithDot(name)) {
            name = name.substring(0, name.length() - DOT_SUFFIX.length());
        }
        return inputFile.resolveSibling(name + "-output" + DOT_SUFFIX);
    }

    /** Whether {@code name} already ends in {@code .dot}, in any case — {@code .DOT} counts. */
    private static boolean endsWithDot(String name) {
        return name.regionMatches(true, name.length() - DOT_SUFFIX.length(),
                DOT_SUFFIX, 0, DOT_SUFFIX.length());
    }

    /** The value following an option flag, e.g. the {@code N} of {@code -p N}. */
    private static String optionValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new CliArgumentException("option '" + option + "' requires a value");
        }
        return args[index];
    }

    private static int parsePositiveInt(String value, String what) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new CliArgumentException(what + " must be an integer, got '" + value + "'");
        }
        if (parsed < 1) {
            throw new CliArgumentException(what + " must be at least 1, got " + parsed);
        }
        return parsed;
    }
}