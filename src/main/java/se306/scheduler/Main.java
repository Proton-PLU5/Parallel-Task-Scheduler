package se306.scheduler;

import java.io.IOException;

import se306.scheduler.cli.CliArgumentException;
import se306.scheduler.cli.CliArguments;
import se306.scheduler.graph.GraphValidationException;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.io.DotOutputWriter;
import se306.scheduler.io.DotParseException;
import se306.scheduler.io.DotParser;
import se306.scheduler.schedule.DFSBranchAndBound;
import se306.scheduler.schedule.ListScheduler;
import se306.scheduler.schedule.Schedule;

/**
 * Entry point: {@code java -jar scheduler.jar INPUT.dot P [-p N] [-v] [-o OUTPUT]}.
 *
 * <p>The whole pipeline runs here: {@link CliArguments} parses the command line, {@link DotParser}
 * reads the graph (WBS 2.2/2.3), {@link ListScheduler} schedules it and {@link DotOutputWriter}
 * writes the result (WBS 2.4). The stages still outstanding — the optimal search (WBS 3.x) and
 * visualisation — are marked with TODOs below; everything they need is already in {@code arguments}.
 *
 * <p>The schedule is always written to a file: to {@code OUTPUT} when {@code -o OUTPUT} is given, and
 * to {@code INPUT-output.dot} beside the input otherwise, as {@link CliArguments} decides.
 *
 * <p>Exit status: 0 on success, 1 on an unreadable input or unwritable output, 2 on bad
 * command-line arguments.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        CliArguments arguments;
        try {
            arguments = CliArguments.parse(args);
        } catch (CliArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
            return;
        }

        TaskGraph graph;
        Schedule schedule;
        try {
            graph = new DotParser().parse(arguments.inputFile());

            // TODO (WBS 3.x): replace the greedy scheduler with the branch-and-bound search, using
            // arguments.coreCount() cores and visualising the search when arguments.visualise().
            schedule = new ListScheduler(graph, arguments.processorCount()).solve();
        } catch (DotParseException | GraphValidationException | IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
            return;
        } catch (IOException e) {
            System.err.println(
                    "Error: could not read '" + arguments.inputFile() + "': " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            new DotOutputWriter().write(graph, schedule, arguments.outputFile());
        } catch (IOException e) {
            System.err.println(
                    "Error: could not write '" + arguments.outputFile() + "': " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println(schedule + " written to " + arguments.outputFile());
    }
}