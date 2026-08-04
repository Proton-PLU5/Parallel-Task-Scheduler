package se306.scheduler;

import java.io.IOException;
import java.nio.file.Path;

import se306.scheduler.graph.GraphValidationException;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.io.DotParseException;
import se306.scheduler.io.DotParser;

/**
 * Entry point for the input-parsing slice of the project (WBS 2.2/2.3): reads a DOT file into a
 * {@link TaskGraph}.
 *
 * <p>Scheduling itself (WBS 3.x) and the DOT output writer (WBS 2.4) live on other branches, so
 * there is nothing to schedule or write here yet — this main exists to run the parser end to end by
 * hand. It is silent on success and reports parse failures on stderr with a non-zero exit status.
 */
public final class Main {

    /** Used when no input file is given on the command line. */
    private static final Path DEFAULT_INPUT = Path.of("example.dot");

    private Main() {
    }

    public static void main(String[] args) {
        Path input = args.length > 0 ? Path.of(args[0]) : DEFAULT_INPUT;
        try {
            new DotParser().parse(input);
        } catch (DotParseException | GraphValidationException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error: could not read '" + input + "': " + e.getMessage());
            System.exit(1);
        }
    }
}