package se306.scheduler.io;

import java.io.StringReader;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jgrapht.alg.util.Pair;
import org.jgrapht.nio.ImportException;
import org.jgrapht.nio.dot.DOTEventDrivenImporter;

import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;

/**
 * Turns DOT source into a {@link TaskGraph} using JGraphT's event-driven DOT importer, so the full
 * DOT grammar — comments, {@code strict}, quoted names and values, attribute defaults, subgraphs,
 * statements without semicolons — is handled by a real parser instead of our own line matching.
 *
 * <p>The importer streams events rather than building a JGraphT graph: each task, dependency and
 * {@code Weight} attribute is fed straight into a {@link GraphBuilder}, which still produces the
 * project's own index-based {@link TaskGraph} — JGraphT is used only at this I/O boundary, never in
 * the search.
 *
 * <p>Attributes other than {@code Weight} (colours, labels, shapes, …) are legal noise and are
 * ignored, but a task or dependency <em>without</em> a {@code Weight} is still an error: the graph
 * must never silently shrink. Structural validation (unknown names, self-loops, duplicates, cycles)
 * stays in {@link GraphBuilder#build()}.
 */
final class JGraphTDotReader {

    private static final String WEIGHT_KEY = "Weight";

    /** Graph name reported by the importer under this key; see {@code DEFAULT_GRAPH_ID_KEY}. */
    private static final String GRAPH_ID_KEY = DOTEventDrivenImporter.DEFAULT_GRAPH_ID_KEY;

    /**
     * Parses DOT source and builds the graph.
     *
     * @throws DotParseException                            on malformed DOT or a missing/non-integer
     *                                                      {@code Weight}
     * @throws se306.scheduler.graph.GraphValidationException if the declarations do not form a valid
     *                                                      task graph
     */
    TaskGraph read(String source) {
        GraphBuilder builder = new GraphBuilder();

        // Everything the importer mentions, and the subset that carried a Weight; the difference is
        // reported as an error after the parse, so a weightless declaration cannot be dropped.
        Set<String> tasksSeen = new LinkedHashSet<>();
        Set<String> tasksWithWeight = new LinkedHashSet<>();
        Map<Pair<String, String>, Integer> edgesSeen = new HashMap<>();
        Map<Pair<String, String>, Integer> edgesWithWeight = new HashMap<>();

        DOTEventDrivenImporter importer = new DOTEventDrivenImporter();

        importer.addGraphAttributeConsumer((key, attribute) -> {
            if (GRAPH_ID_KEY.equals(key)) {
                builder.graphName(attribute.getValue());
            }
        });

        importer.addVertexConsumer(tasksSeen::add);
        importer.addVertexAttributeConsumer((taskAndKey, attribute) -> {
            if (WEIGHT_KEY.equals(taskAndKey.getSecond())) {
                String task = taskAndKey.getFirst();
                builder.addNode(task, weight(attribute.getValue(), "task '" + task + "'"));
                tasksWithWeight.add(task);
            }
        });

        importer.addEdgeConsumer(edge -> edgesSeen.merge(edge, 1, Integer::sum));
        importer.addEdgeAttributeConsumer((edgeAndKey, attribute) -> {
            if (WEIGHT_KEY.equals(edgeAndKey.getSecond())) {
                Pair<String, String> edge = edgeAndKey.getFirst();
                String described = "dependency '" + edge.getFirst() + " -> " + edge.getSecond() + "'";
                builder.addEdge(edge.getFirst(), edge.getSecond(),
                        weight(attribute.getValue(), described));
                edgesWithWeight.merge(edge, 1, Integer::sum);
            }
        });

        try {
            importer.importInput(new StringReader(source));
        } catch (ImportException e) {
            throw new DotParseException(0, "not valid DOT: " + e.getMessage());
        }

        for (String task : tasksSeen) {
            if (!tasksWithWeight.contains(task)) {
                throw new DotParseException(0, "task '" + task + "' has no Weight attribute.");
            }
        }
        for (Map.Entry<Pair<String, String>, Integer> entry : edgesSeen.entrySet()) {
            if (edgesWithWeight.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                Pair<String, String> edge = entry.getKey();
                throw new DotParseException(0, "dependency '" + edge.getFirst() + " -> "
                        + edge.getSecond() + "' has no Weight attribute.");
            }
        }

        return builder.build();
    }

    /** {@code Weight} values arrive unquoted; anything but an integer is an error. */
    private static int weight(String raw, String owner) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new DotParseException(0,
                    "Weight '" + raw + "' of " + owner + " is not an integer.");
        }
    }
}
