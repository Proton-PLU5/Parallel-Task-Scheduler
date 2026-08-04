package se306.scheduler.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;

/**
 * Reads a task graph in DOT format (WBS 2.2).
 *
 * <p>The input format is fixed and narrow, so the parser is a line matcher rather than a DOT
 * grammar. Every file looks like {@code example.dot}: a header, then one task or one dependency per
 * line, then a closing brace.
 *
 * <pre>{@code
 * digraph "example" {
 *     A        [Weight=2];
 *     B        [Weight=3];
 *     A -> B   [Weight=3];
 * }
 * }</pre>
 *
 * <p>Task names are alphanumeric (plus {@code _}) and weights are integers. Whitespace around and
 * inside a declaration is free-form; anything else is a parse error, reported with its line number
 * rather than skipped, so a malformed input can never silently become a smaller graph.
 *
 * <p>Names may appear in any order — an edge may reference a task declared further down — so
 * resolution and validation are deferred to {@link GraphBuilder#build()}.
 */
public final class DotParser {

    /** {@code digraph "example" {} — the name is optionally quoted. */
    private static final Pattern HEADER =
            Pattern.compile("digraph\\s+(?:\"([^\"]*)\"|(\\w+))\\s*\\{");

    /** {@code A [Weight=2];} */
    private static final Pattern NODE =
            Pattern.compile("(\\w+)\\s*\\[\\s*Weight\\s*=\\s*(-?\\d+)\\s*]\\s*;");

    /** {@code A -> B [Weight=3];} */
    private static final Pattern EDGE =
            Pattern.compile("(\\w+)\\s*->\\s*(\\w+)\\s*\\[\\s*Weight\\s*=\\s*(-?\\d+)\\s*]\\s*;");

    /** Parses the DOT file at {@code path}. */
    public TaskGraph parse(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Parses DOT source held in memory. */
    public TaskGraph parse(String source) {
        GraphBuilder builder = new GraphBuilder();
        String[] lines = source.split("\\R");

        for (int i = 0; i < lines.length; i++) {
            int line = i + 1;
            String text = lines[i].trim();
            if (text.isEmpty() || text.equals("}")) {
                continue;
            }

            Matcher node = NODE.matcher(text);
            if (node.matches()) {
                builder.addNode(node.group(1), weight(node.group(2), line));
                continue;
            }

            Matcher edge = EDGE.matcher(text);
            if (edge.matches()) {
                builder.addEdge(edge.group(1), edge.group(2), weight(edge.group(3), line));
                continue;
            }

            Matcher header = HEADER.matcher(text);
            if (header.matches()) {
                // Group 1 is the quoted form, group 2 the bare one; exactly one of them matched.
                builder.graphName(header.group(1) != null ? header.group(1) : header.group(2));
                continue;
            }

            throw new DotParseException(line, "expected a task 'A [Weight=1];', a dependency "
                    + "'A -> B [Weight=1];' or the graph header, but got '" + text + "'.");
        }
        return builder.build();
    }

    /** The regex guarantees the digits; only a value too large for an {@code int} can fail here. */
    private static int weight(String raw, int line) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new DotParseException(line, "weight " + raw + " does not fit in an int.");
        }
    }
}