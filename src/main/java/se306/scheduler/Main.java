package se306.scheduler;

import java.io.IOException;
import java.nio.file.Path;

import se306.scheduler.graph.GraphValidationException;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.io.DotParseException;
import se306.scheduler.io.DotParser;
import javafx.application.Application;
import se306.scheduler.gui.MainWindow;
import se306.scheduler.schedule.ListScheduler;
import se306.scheduler.schedule.Schedule;

/**
 * Entry point for the parsing + scheduling slice of the project (WBS 2.2/2.3, 3.x): reads a DOT
 * file into a {@link TaskGraph} and runs the greedy {@link ListScheduler} over it.
 *
 * <p>The DOT output writer (WBS 2.4) lives on another branch, so the resulting {@link Schedule} is
 * only printed to stdout for now rather than written to a file.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java -jar scheduler.jar INPUT.dot P [-v]");
            System.exit(1);
            return;
        }

        Path input = Path.of(args[0]);
        boolean visualise = containsFlag(args, "-v");

        int numProcessors;
        try {
            numProcessors = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("Error: P must be an integer number of processors, was '" + args[1] + "'.");
            System.exit(1);
            return;
        }

        try {
            TaskGraph graph = new DotParser().parse(input);
            if (visualise) {
                Schedule schedule = new ListScheduler(graph, numProcessors).solve();
                MainWindow.setSchedule(graph, schedule);
                Application.launch(MainWindow.class, args);
            } else {
                Schedule schedule = new ListScheduler(graph, numProcessors).solve();
                System.out.println(schedule);
            }
        } catch (DotParseException | GraphValidationException | IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error: could not read '" + input + "': " + e.getMessage());
            System.exit(1);
        }
    }

    private static boolean containsFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) return true;
        }
        return false;
    }
}