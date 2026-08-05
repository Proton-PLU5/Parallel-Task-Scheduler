package se306.scheduler.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;

/**
 * Reads a task graph in DOT format (WBS 2.2).
 *
 * <p>The heavy lifting is delegated to {@link JGraphTDotReader}, which drives JGraphT's DOT
 * importer over the source and feeds our {@link GraphBuilder}, so anything Graphviz accepts —
 * comments, {@code strict}, quoted names, extra attributes, subgraphs — parses here too:
 *
 * <pre>{@code
 * digraph "example" {
 *     A        [Weight=2];
 *     B        [Weight=3];
 *     A -> B   [Weight=3];
 * }
 * }</pre>
 *
 * <p>Every task and dependency must carry an integer {@code Weight}; one without it is a
 * {@link DotParseException}, so a malformed input can never silently become a smaller graph.
 * Structural validation (unknown names, self-loops, cycles) happens in
 * {@link GraphBuilder#build()}.
 */
public final class DotParser {

    /** Parses the DOT file at {@code path}. */
    public TaskGraph parse(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Parses DOT source held in memory. */
    public TaskGraph parse(String source) {
        return new JGraphTDotReader().read(source);
    }
}