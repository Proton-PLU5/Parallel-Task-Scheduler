package se306.scheduler.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se306.scheduler.graph.TaskGraph;

/**
 * Tests the DOT input parser (WBS 2.2) against {@code test3.dot} in the repository root — a stress
 * test that buries the Figure 1 graph under legal DOT noise the parser must ignore: block, line and
 * hash comments, a {@code strict} header, graph/node/edge attribute defaults, a standalone
 * attribute statement, a junk subgraph, quoted names and weights, extra non-Weight attributes, and
 * statements without trailing semicolons.
 *
 * <p>The real graph hidden in the file:
 *
 * <pre>
 *        a(2)
 *       /    \
 *     1/      \2
 *     b(3)    c(3)
 *       \    /
 *       2\  /1
 *        d(2)
 * </pre>
 */
class DotParserTest2 {

    private TaskGraph graph;
    private int a;
    private int b;
    private int c;
    private int d;

    @BeforeEach
    void parseTest3Dot() throws IOException {
        // Maven sets basedir to the project root; the fallback covers running from an IDE.
        Path test3Dot = Path.of(System.getProperty("basedir", ".")).resolve("test3.dot");
        assertTrue(Files.exists(test3Dot),
                "test3.dot not found at " + test3Dot.toAbsolutePath());

        graph = new DotParser().parse(test3Dot);

        a = graph.indexOf("a");
        b = graph.indexOf("b");
        c = graph.indexOf("c");
        d = graph.indexOf("d");
    }

    @Test
    @DisplayName("test3.dot: the quoted graph name survives the strict header")
    void graphName() {
        assertEquals("test3", graph.graphName());
    }

    @Test
    @DisplayName("test3.dot: parses exactly the four tasks buried in the noise")
    void nodeCount() {
        assertEquals(4, graph.taskCount());

        assertTrue(a >= 0, "a should be present");
        assertTrue(b >= 0, "b should be present (declared with a quoted name)");
        assertTrue(c >= 0, "c should be present");
        assertTrue(d >= 0, "d should be present");
    }

    @Test
    @DisplayName("test3.dot: task weights are read from Weight, ignoring the other attributes")
    void nodeWeights() {
        assertEquals(2, graph.weight(a), "a");
        assertEquals(3, graph.weight(b), "b (weight is quoted in the file)");
        assertEquals(3, graph.weight(c), "c");
        assertEquals(2, graph.weight(d), "d");
    }

    @Test
    @DisplayName("test3.dot: parses exactly the four dependencies in the file")
    void edgeCount() {
        assertEquals(4, graph.edgeCount());
    }

    @Test
    @DisplayName("test3.dot: each declared edge is present, in the right direction")
    void edgesArePresent() {
        assertTrue(graph.hasEdge(a, b), "a -> b");
        assertTrue(graph.hasEdge(a, c), "a -> c");
        assertTrue(graph.hasEdge(b, d), "b -> d (declared with no spaces or semicolon)");
        assertTrue(graph.hasEdge(c, d), "c -> d");
    }

    @Test
    @DisplayName("test3.dot: edges that were not declared are absent")
    void undeclaredEdgesAreAbsent() {
        assertFalse(graph.hasEdge(a, d), "there is no direct a -> d edge");
        assertFalse(graph.hasEdge(b, c), "b and c are independent");
        assertFalse(graph.hasEdge(b, a), "edges are directed, so b -> a is not a -> b");
    }

    @Test
    @DisplayName("test3.dot: communication costs are the edge weights, ignoring other attributes")
    void edgeCosts() {
        assertEquals(1, graph.commCost(a, b), "a -> b");
        assertEquals(2, graph.commCost(a, c), "a -> c");
        assertEquals(2, graph.commCost(b, d), "b -> d");
        assertEquals(1, graph.commCost(c, d), "c -> d");
    }
}