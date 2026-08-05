package se306.scheduler;

import java.io.IOException;

import se306.scheduler.cli.CliArgumentException;
import se306.scheduler.cli.CliArguments;
import se306.scheduler.graph.GraphValidationException;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.io.DotParseException;
import se306.scheduler.io.DotParser;
import se306.scheduler.schedule.ListScheduler;
import se306.scheduler.schedule.Schedule;

/**
 * Entry point: {@code java -jar scheduler.jar INPUT.dot P [-p N] [-v] [-o OUTPUT]}.
 *
 * <p>The command line is parsed into {@link CliArguments}, the input graph is read (WBS 2.2/2.3) and
 * the greedy {@link ListScheduler} runs over it. The stages that are still outstanding — the optimal
 * search (WBS 3.x), the DOT output writer (WBS 2.4) and visualisation — are marked with TODOs below;
 * everything each of them needs is already in {@code arguments}.
 *
 * <p>Exit status: 0 on success, 1 on a bad input file, 2 on bad command-line arguments.
 */
public final class Main {

    private static final String USAGE = """
            Usage: java -jar scheduler.jar INPUT.dot P [OPTION]
              INPUT.dot  a task graph with integer weights in dot format
              P          number of processors to schedule the INPUT graph on
            Options:
              -p N       use N cores for execution in parallel (default is sequential)
              -v         visualise the search
              -o OUTPUT  output file is named OUTPUT (default is INPUT-output.dot)""";

    private Main() {
    }

    public static void main(String[] args) {
        CliArguments arguments;
        try {
            arguments = CliArguments.parse(args);
        } catch (CliArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        try {
            TaskGraph graph = new DotParser().parse(arguments.inputFile());

            // TODO (WBS 3.x): replace the greedy scheduler with the branch-and-bound search, using
            // arguments.coreCount() cores and visualising the search when arguments.visualise().
            Schedule schedule = new ListScheduler(graph, arguments.processorCount()).solve();
            System.out.println(schedule);

            // TODO (WBS 2.4): write the schedule to arguments.outputFile() with the DOT writer.
        } catch (DotParseException | GraphValidationException | IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println(
                    "Error: could not read '" + arguments.inputFile() + "': " + e.getMessage());
            System.exit(1);
        }
    }
}