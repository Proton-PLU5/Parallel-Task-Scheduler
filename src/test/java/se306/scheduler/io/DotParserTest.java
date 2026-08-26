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
 * Tests the DOT input parser (WBS 2.2) against the two sample graphs in the repository root.
 *
 * <p>{@code example.dot}, the example graph from the project description:
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
 *
 * <p>{@code test2.dot} — the same diamond shape, but with different task and edge weights, so a
 * weight read from the wrong place cannot pass both suites:
 *
 * <pre>
 *        A(4)
 *       /    \
 *     1/      \1
 *     B(5)    C(5)
 *       \    /
 *       1\  /1
 *        D(3)
 * </pre>
 */
class DotParserTest {

    private TaskGraph graph;
    private int a;
    private int b;
    private int c;
    private int d;

    private TaskGraph graph2;
    private int a2;
    private int b2;
    private int c2;
    private int d2;

    @BeforeEach
    void parseExampleDot() throws IOException {
        // Maven sets basedir to the project root; the fallback covers running from an IDE.
        Path exampleDot = Path.of(System.getProperty("basedir", ".")).resolve("testcases/example.dot");
        assertTrue(Files.exists(exampleDot),
                "example.dot not found at " + exampleDot.toAbsolutePath());

        graph = new DotParser().parse(exampleDot);

        a = graph.indexOf("A");
        b = graph.indexOf("B");
        c = graph.indexOf("C");
        d = graph.indexOf("D");
    }

    @BeforeEach
    void parseTest2Dot() throws IOException {
        Path test2Dot = Path.of(System.getProperty("basedir", ".")).resolve("testcases/test2.dot");
        assertTrue(Files.exists(test2Dot),
                "test2.dot not found at " + test2Dot.toAbsolutePath());

        graph2 = new DotParser().parse(test2Dot);

        a2 = graph2.indexOf("A");
        b2 = graph2.indexOf("B");
        c2 = graph2.indexOf("C");
        d2 = graph2.indexOf("D");
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

    // --- test2.dot ------------------------------------------------------------------------------

    @Test
    @DisplayName("test2.dot: parses exactly the four tasks in the file")
    void test2NodeCount() {
        assertEquals(4, graph2.taskCount());

        assertTrue(a2 >= 0, "A should be present");
        assertTrue(b2 >= 0, "B should be present");
        assertTrue(c2 >= 0, "C should be present");
        assertTrue(d2 >= 0, "D should be present");
    }

    @Test
    @DisplayName("test2.dot: task weights are the execution times from the file")
    void test2NodeWeights() {
        assertEquals(4, graph2.weight(a2), "A");
        assertEquals(5, graph2.weight(b2), "B");
        assertEquals(5, graph2.weight(c2), "C");
        assertEquals(3, graph2.weight(d2), "D");
    }

    @Test
    @DisplayName("test2.dot: parses exactly the four dependencies in the file")
    void test2EdgeCount() {
        assertEquals(4, graph2.edgeCount());
    }

    @Test
    @DisplayName("test2.dot: each declared edge is present, in the right direction")
    void test2EdgesArePresent() {
        assertTrue(graph2.hasEdge(a2, b2), "A -> B");
        assertTrue(graph2.hasEdge(a2, c2), "A -> C");
        assertTrue(graph2.hasEdge(b2, d2), "B -> D");
        assertTrue(graph2.hasEdge(c2, d2), "C -> D");
    }

    @Test
    @DisplayName("test2.dot: edges that were not declared are absent")
    void test2UndeclaredEdgesAreAbsent() {
        assertFalse(graph2.hasEdge(a2, d2), "there is no direct A -> D edge");
        assertFalse(graph2.hasEdge(b2, c2), "B and C are independent");
        assertFalse(graph2.hasEdge(b2, a2), "edges are directed, so B -> A is not A -> B");
    }

    @Test
    @DisplayName("test2.dot: communication costs are the edge weights from the file")
    void test2EdgeCosts() {
        assertEquals(1, graph2.commCost(a2, b2), "A -> B");
        assertEquals(1, graph2.commCost(a2, c2), "A -> C");
        assertEquals(1, graph2.commCost(b2, d2), "B -> D");
        assertEquals(1, graph2.commCost(c2, d2), "C -> D");
    }
}
