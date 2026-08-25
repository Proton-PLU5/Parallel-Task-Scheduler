package se306.scheduler.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link GraphBuilder}'s validation. Every way a DOT file can declare something that is not
 * a valid task graph must be rejected with a {@link GraphValidationException}, while the two
 * harmless repeats DOT allows (same task twice with the same weight, same edge twice with the same
 * weight) must be accepted.
 */
class GraphBuilderTest {

    @Test
    @DisplayName("a task with a negative weight is rejected")
    void negativeNodeWeightRejected() {
        GraphBuilder builder = new GraphBuilder();

        GraphValidationException error = assertThrows(GraphValidationException.class,
                () -> builder.addNode("A", -1));

        assertTrue(error.getMessage().contains("negative weight"), error.getMessage());
    }

    @Test
    @DisplayName("declaring the same task twice with the same weight is allowed")
    void redeclaringTaskWithSameWeightTolerated() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 2);
        builder.addNode("A", 2);

        TaskGraph graph = builder.build();

        // The repeat changes nothing: there is still exactly one task A with weight 2.
        assertEquals(1, graph.taskCount());
        assertEquals(2, graph.weight(graph.indexOf("A")));
    }

    @Test
    @DisplayName("declaring the same task twice with different weights is rejected")
    void redeclaringTaskWithDifferentWeightRejected() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 2);

        GraphValidationException error = assertThrows(GraphValidationException.class,
                () -> builder.addNode("A", 3));

        assertTrue(error.getMessage().contains("declared twice"), error.getMessage());
    }

    @Test
    @DisplayName("an edge with a negative weight is rejected")
    void negativeEdgeWeightRejected() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 1);
        builder.addNode("B", 1);

        GraphValidationException error = assertThrows(GraphValidationException.class,
                () -> builder.addEdge("A", "B", -3));

        assertTrue(error.getMessage().contains("negative weight"), error.getMessage());
    }

    @Test
    @DisplayName("an edge starting at a task that was never declared is rejected")
    void edgeFromUndeclaredTaskRejected() {
        // The edge A -> B is fine to record, but at build() time A turns out not to exist.
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("B", 1);
        builder.addEdge("A", "B", 1);

        GraphValidationException error = assertThrows(GraphValidationException.class,
                () -> builder.build());

        assertTrue(error.getMessage().contains("undeclared task 'A'"), error.getMessage());
    }

    @Test
    @DisplayName("an edge pointing at a task that was never declared is rejected")
    void edgeToUndeclaredTaskRejected() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 1);
        builder.addEdge("A", "B", 1);

        GraphValidationException error = assertThrows(GraphValidationException.class,
                () -> builder.build());

        assertTrue(error.getMessage().contains("undeclared task 'B'"), error.getMessage());
    }

    @Test
    @DisplayName("a task that depends on itself is rejected")
    void selfLoopRejected() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 1);
        builder.addEdge("A", "A", 1);

        GraphValidationException error = assertThrows(GraphValidationException.class,
                () -> builder.build());

        assertTrue(error.getMessage().contains("depends on itself"), error.getMessage());
    }

    @Test
    @DisplayName("declaring the same edge twice with the same weight is allowed, and kept once")
    void duplicateEdgeWithSameWeightTolerated() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 1);
        builder.addNode("B", 1);
        builder.addEdge("A", "B", 4);
        builder.addEdge("A", "B", 4);

        TaskGraph graph = builder.build();

        assertEquals(1, graph.edgeCount());
        assertEquals(4, graph.commCost(graph.indexOf("A"), graph.indexOf("B")));
    }

    @Test
    @DisplayName("declaring the same edge twice with different weights is rejected")
    void duplicateEdgeWithDifferentWeightRejected() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 1);
        builder.addNode("B", 1);
        builder.addEdge("A", "B", 4);
        builder.addEdge("A", "B", 5);

        GraphValidationException error = assertThrows(GraphValidationException.class,
                () -> builder.build());

        assertTrue(error.getMessage().contains("declared twice"), error.getMessage());
    }

    @Test
    @DisplayName("a graph whose tasks depend on each other in a circle is rejected")
    void cycleRejected() {
        // A needs B, B needs C, C needs A — no task can ever start.
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 1);
        builder.addNode("B", 1);
        builder.addNode("C", 1);
        builder.addEdge("A", "B", 1);
        builder.addEdge("B", "C", 1);
        builder.addEdge("C", "A", 1);

        GraphValidationException error = assertThrows(GraphValidationException.class,
                () -> builder.build());

        assertTrue(error.getMessage().contains("cycle"), error.getMessage());

        // The message should name all three tasks caught in the circle.
        assertTrue(error.getMessage().contains("A"), error.getMessage());
        assertTrue(error.getMessage().contains("B"), error.getMessage());
        assertTrue(error.getMessage().contains("C"), error.getMessage());
    }

    @Test
    @DisplayName("the cycle message blames tasks stuck behind the cycle, but not unrelated ones")
    void taskDownstreamOfCycleReported() {
        // A and B block each other, and D waits on B, so D can never start either.
        // X has no dependencies at all, so X is fine and must not be mentioned.
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 1);
        builder.addNode("B", 1);
        builder.addNode("D", 1);
        builder.addNode("X", 1);
        builder.addEdge("A", "B", 1);
        builder.addEdge("B", "A", 1);
        builder.addEdge("B", "D", 1);

        GraphValidationException error = assertThrows(GraphValidationException.class,
                () -> builder.build());

        assertTrue(error.getMessage().contains("D"), error.getMessage());
        assertFalse(error.getMessage().contains("X"),
                "a task unrelated to the cycle must not be blamed for it: " + error.getMessage());
    }

    @Test
    @DisplayName("looking up a name that is not a task gives -1")
    void indexOfUnknownNameIsMinusOne() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 1);

        TaskGraph graph = builder.build();

        assertEquals(-1, graph.indexOf("Z"));
        assertTrue(graph.indexOf("A") >= 0);
    }
}
