package se306.scheduler.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

/**
 * Writes a solved schedule back out as a DOT file (WBS 2.4) — the counterpart to {@link DotParser},
 * and the last stage of the pipeline.
 *
 * <p>The output is the input graph with the schedule's answers attached: every task keeps its
 * {@code Weight} and gains a {@code Start} and a {@code Processor}, and every dependency keeps its
 * communication cost. Nothing else from the input is reproduced — comments, colours, labels and
 * subgraphs do not survive {@link TaskGraph} and are not part of the required output:
 *
 * <pre>{@code
 * digraph "example" {
 *     A        [Weight=2,Start=0,Processor=1];
 *     B        [Weight=3,Start=2,Processor=2];
 *     A -> B   [Weight=3];
 * }
 * }</pre>
 *
 * <p>Two conventions are worth stating explicitly:
 *
 * <ul>
 *   <li>{@code Processor} is <em>1-based</em>, as in the project description, while
 *       {@link Schedule#processor(int)} is 0-based internally. The conversion happens here and
 *       nowhere else.</li>
 *   <li>Tasks come out in graph index order — the order they were declared in the input — followed
 *       by all dependencies in the same order, each task's successors ascending. The output is
 *       therefore byte-for-byte reproducible for a given graph and schedule, which is what makes it
 *       testable.</li>
 * </ul>
 *
 * <p>The writer trusts the {@link Schedule} it is handed: {@code Schedule}'s own constructor has
 * already checked that every task has a non-negative start time on a processor that exists, and the
 * search is responsible for precedence and overlap. The one thing checked here is that the schedule
 * and the graph are the same size, since writing a schedule against the wrong graph would otherwise
 * produce a plausible-looking but meaningless file.
 */
public final class DotOutputWriter {

    /** Indentation, and the gap before an attribute list — matches the layout of the input files. */
    private static final String INDENT = "\t";
    private static final String GAP = "\t ";

    /** Fixed rather than {@code System.lineSeparator()} so the output does not vary by platform. */
    private static final String NEWLINE = "\n";

    /** Processors are numbered from 1 in the output, from 0 in {@link Schedule}. */
    private static final int FIRST_PROCESSOR = 1;

    /** Reserved words are never bare identifiers, so a task named {@code node} must be quoted. */
    private static final Set<String> DOT_KEYWORDS =
            Set.of("strict", "graph", "digraph", "subgraph", "node", "edge");

    /**
     * Writes the schedule to {@code output}, replacing any existing file and creating the parent
     * directory if it is missing, so {@code -o results/schedule.dot} works on a clean checkout.
     *
     * @throws IOException              if the file cannot be created or written
     * @throws IllegalArgumentException if {@code schedule} does not cover exactly {@code graph}'s tasks
     */
    public void write(TaskGraph graph, Schedule schedule, Path output) throws IOException {
        String dot = toDot(graph, schedule);
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, dot, StandardCharsets.UTF_8);
    }

    /**
     * Renders the schedule as DOT source, for callers that want the text rather than a file — the
     * tests, and eventually the visualisation.
     *
     * @throws IllegalArgumentException if {@code schedule} does not cover exactly {@code graph}'s tasks
     */
    public String toDot(TaskGraph graph, Schedule schedule) {
        if (schedule.taskCount() != graph.taskCount()) {
            throw new IllegalArgumentException("schedule covers " + schedule.taskCount()
                    + " tasks but graph '" + graph.graphName() + "' has " + graph.taskCount() + ".");
        }

        StringBuilder out = new StringBuilder(32 + 40 * graph.taskCount() + 24 * graph.edgeCount());
        out.append("digraph ").append(quoted(graph.graphName())).append(" {").append(NEWLINE);

        for (int task = 0; task < graph.taskCount(); task++) {
            out.append(INDENT).append(identifier(graph.name(task))).append(GAP)
                    .append("[Weight=").append(graph.weight(task))
                    .append(",Start=").append(schedule.startTime(task))
                    .append(",Processor=").append(schedule.processor(task) + FIRST_PROCESSOR)
                    .append("];").append(NEWLINE);
        }

        for (int from = 0; from < graph.taskCount(); from++) {
            for (int k = graph.childStart(from); k < graph.childEnd(from); k++) {
                int to = graph.childAt(k);
                out.append(INDENT).append(identifier(graph.name(from))).append(" -> ")
                        .append(identifier(graph.name(to))).append(GAP)
                        .append("[Weight=").append(graph.commCost(from, to)).append("];")
                        .append(NEWLINE);
            }
        }

        return out.append("}").append(NEWLINE).toString();
    }

    /** A task name as DOT: bare when it is a plain identifier, quoted when it is not. */
    private static String identifier(String name) {
        return isBareIdentifier(name) ? name : quoted(name);
    }

    /**
     * Whether {@code name} can be written without quotes: an ASCII letter or underscore followed by
     * letters, digits and underscores, and not a DOT keyword. Anything else — spaces, punctuation, a
     * leading digit, non-ASCII — is quoted instead, which is always legal.
     */
    private static boolean isBareIdentifier(String name) {
        if (name.isEmpty() || DOT_KEYWORDS.contains(name.toLowerCase(Locale.ROOT))) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
            boolean digitAfterFirst = i > 0 && c >= '0' && c <= '9';
            if (!letter && !digitAfterFirst) {
                return false;
            }
        }
        return true;
    }

    /** Wraps {@code value} in double quotes, escaping any quote or backslash inside it. */
    private static String quoted(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append('"').toString();
    }
}