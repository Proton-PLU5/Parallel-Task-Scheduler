package se306.scheduler.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se306.scheduler.graph.TaskGraph;

/**
 * Tests the DOT input parser (WBS 2.2) against {@code example.dot}, the example graph from the
 * project description:
 *
 * <pre>
 *        A(2)
 *       /    \
 *     3/      \3
 *     B(3)    C(6)
 *       \    /
 *       2\  /8
 *        D(9)
 * </pre>
 */
class DotParserTest {

    private TaskGraph graph;
    private int a;
    private int b;
    private int c;
    private int d;

    @BeforeEach
    void parseExampleDot() throws IOException {
        // Maven sets basedir to the project root; the fallback covers running from an IDE.
        Path exampleDot = Path.of(System.getProperty("basedir", ".")).resolve("example.dot");
        assertTrue(Files.exists(exampleDot),
                "example.dot not found at " + exampleDot.toAbsolutePath());

        graph = new DotParser().parse(exampleDot);

        a = graph.indexOf("A");
        b = graph.indexOf("B");
        c = graph.indexOf("C");
        d = graph.indexOf("D");
    }

    @Test
    @DisplayName("parses exactly the four tasks in the file")
    void nodeCount() {
        assertEquals(4, graph.taskCount());

        assertTrue(a >= 0, "A should be present");
        assertTrue(b >= 0, "B should be present");
        assertTrue(c >= 0, "C should be present");
        assertTrue(d >= 0, "D should be present");
    }

    @Test
    @DisplayName("task weights are the execution times from the file")
    void nodeWeights() {
        assertEquals(2, graph.weight(a), "A");
        assertEquals(3, graph.weight(b), "B");
        assertEquals(6, graph.weight(c), "C");
        assertEquals(9, graph.weight(d), "D");
    }

    @Test
    @DisplayName("parses exactly the four dependencies in the file")
    void edgeCount() {
        assertEquals(4, graph.edgeCount());
    }

    @Test
    @DisplayName("each declared edge is present, in the right direction")
    void edgesArePresent() {
        assertTrue(graph.hasEdge(a, b), "A -> B");
        assertTrue(graph.hasEdge(a, c), "A -> C");
        assertTrue(graph.hasEdge(b, d), "B -> D");
        assertTrue(graph.hasEdge(c, d), "C -> D");
    }

    @Test
    @DisplayName("edges that were not declared are absent")
    void undeclaredEdgesAreAbsent() {
        assertFalse(graph.hasEdge(a, d), "there is no direct A -> D edge");
        assertFalse(graph.hasEdge(b, c), "B and C are independent");
        assertFalse(graph.hasEdge(b, a), "edges are directed, so B -> A is not A -> B");
    }

    @Test
    @DisplayName("communication costs are the edge weights from the file")
    void edgeCosts() {
        assertEquals(3, graph.commCost(a, b), "A -> B");
        assertEquals(3, graph.commCost(a, c), "A -> C");
        assertEquals(2, graph.commCost(b, d), "B -> D");
        assertEquals(8, graph.commCost(c, d), "C -> D");
    }
}
