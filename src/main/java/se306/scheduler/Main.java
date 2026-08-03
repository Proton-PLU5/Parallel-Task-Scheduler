package se306.scheduler;

import java.io.IOException;

import se306.scheduler.cli.CommandLineArgs;
import se306.scheduler.graph.GraphValidationException;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.io.DotOutputWriter;
import se306.scheduler.io.DotParseException;
import se306.scheduler.io.DotParser;
import se306.scheduler.schedule.Schedule;

/** Entry point: read the task graph, schedule it, write the result. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        try {
            CommandLineArgs options = CommandLineArgs.parse(args);
            TaskGraph graph = new DotParser().parse(options.inputFile());

            Schedule schedule = schedule(graph, options.processorCount());

            new DotOutputWriter().write(graph, schedule, options.outputFile());
            System.out.println("Wrote " + options.outputFile()
                    + " (makespan " + schedule.makespan(graph) + ").");
        } catch (IllegalArgumentException | DotParseException | GraphValidationException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error: could not read or write file: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Placeholder for the branch-and-bound engine (WBS 3.x): runs every task back-to-back on the
     * first processor in topological order. That is always a valid schedule — it respects every
     * dependency and incurs no communication cost — but it is not optimal, and it ignores the
     * processor count entirely.
     *
     * <p>Replace this with the real search; the {@link Schedule} it returns is the contract.
     */
    private static Schedule schedule(TaskGraph graph, int processorCount) {
        int[] startTimes = new int[graph.taskCount()];
        int[] processors = new int[graph.taskCount()];
        int time = 0;
        for (int task : graph.topologicalOrder()) {
            startTimes[task] = time;
            processors[task] = 0;
            time += graph.weight(task);
        }
        return new Schedule(startTimes, processors);
    }
}