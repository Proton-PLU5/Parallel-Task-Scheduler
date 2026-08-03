package se306.scheduler.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

/**
 * Writes a scheduled task graph back out as DOT (WBS 2.4) — the input graph with {@code Start} and
 * {@code Processor} added to every task.
 *
 * <p>{@link TaskGraph} carries everything needed, so the original file text is never retained. Tasks
 * are emitted first, then edges; the output order need not match the input's.
 */
public final class DotOutputWriter {

    private static final String INDENT = "\t";

    /** A DOT identifier that needs no quoting: an alphanumeric name, or a numeral. */
    private static final Pattern BARE_ID =
            Pattern.compile("[a-zA-Z_\\x80-\\uFFFF][a-zA-Z_0-9\\x80-\\uFFFF]*|-?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)");

    /** Writes to {@code path}, creating or truncating it. */
    public void write(TaskGraph graph, Schedule schedule, Path path) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            write(graph, schedule, out);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Writes to any {@link Appendable}, so tests can render straight to a {@link StringBuilder}. */
    public void write(TaskGraph graph, Schedule schedule, Appendable out) throws IOException {
        if (schedule.taskCount() != graph.taskCount()) {
            throw new IllegalArgumentException("schedule covers " + schedule.taskCount()
                    + " tasks but the graph has " + graph.taskCount() + ".");
        }

        out.append("digraph ").append(id(graph.graphName())).append(" {\n");

        for (int task = 0; task < graph.taskCount(); task++) {
            out.append(INDENT).append(id(graph.name(task)))
               .append(" [Weight=").append(Integer.toString(graph.weight(task)))
               .append(",Start=").append(Integer.toString(schedule.startTime(task)))
               // Processors are 0-based internally and 1-based in the output format.
               .append(",Processor=").append(Integer.toString(schedule.processor(task) + 1))
               .append("];\n");
        }

        for (int from = 0; from < graph.taskCount(); from++) {
            for (int k = graph.childStart(from); k < graph.childEnd(from); k++) {
                int to = graph.childAt(k);
                out.append(INDENT).append(id(graph.name(from))).append(" -> ")
                   .append(id(graph.name(to)))
                   .append(" [Weight=").append(Integer.toString(graph.commCost(from, to)))
                   .append("];\n");
            }
        }

        out.append("}\n");
    }

    /**
     * Default output location for an input file: {@code INPUT.dot -> INPUT-output.dot}, alongside
     * the input. Built with {@link Path} so it is correct on Linux and Windows alike.
     */
    public static Path defaultOutputPath(Path input) {
        String fileName = input.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot < 0 ? fileName : fileName.substring(0, dot);
        Path parent = input.toAbsolutePath().getParent();
        return parent.resolve(stem + "-output.dot");
    }

    /** Renders a task name as a DOT identifier, quoting and escaping it only when necessary. */
    private static String id(String name) {
        if (BARE_ID.matcher(name).matches()) {
            return name;
        }
        return '"' + name.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}