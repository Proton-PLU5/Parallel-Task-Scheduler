package se306.scheduler.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;

/**
 * Tests the search's partial-schedule bookkeeping directly: which task is "ready" (all of its
 * dependencies already scheduled) as tasks are placed and then un-placed while backtracking.
 * {@link SequentialAlgorithm} is used as the concrete search, but everything tested here lives in
 * {@link AbstractSearch}.
 */
class AbstractSearchTest {

    /** Two tasks where A(3) must finish before B(4) can start. */
    private static TaskGraph chainOfTwo() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 3);
        builder.addNode("B", 4);
        builder.addEdge("A", "B", 1);
        return builder.build();
    }

    @Test
    @DisplayName("tasks become ready in dependency order, and -1 means nothing is left")
    void nextReadyTaskTracksPlacements() {
        TaskGraph graph = chainOfTwo();
        int a = graph.indexOf("A");
        int b = graph.indexOf("B");
        SequentialAlgorithm search = new SequentialAlgorithm(graph, 1);

        // Before anything is placed, only A is ready — B is still waiting on A.
        assertEquals(a, search.nextReadyTask());

        // Placing A unblocks B.
        search.place(a, 0);
        assertEquals(b, search.nextReadyTask());

        // With both tasks placed there is nothing left to schedule.
        search.place(b, 0);
        assertEquals(-1, search.nextReadyTask());
    }

    @Test
    @DisplayName("a blocked task is skipped over even when it comes first in declaration order")
    void blockedTaskIsSkippedOver() {
        // B is declared before A, so B gets the lower task index — but B depends on A,
        // so the search must skip past B and pick A as the first ready task.
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("B", 4);
        builder.addNode("A", 3);
        builder.addEdge("A", "B", 1);
        TaskGraph graph = builder.build();

        SequentialAlgorithm search = new SequentialAlgorithm(graph, 1);

        assertEquals(graph.indexOf("A"), search.nextReadyTask());
    }

    @Test
    @DisplayName("undo restores readiness, so a backtracked task can be placed again")
    void undoRestoresReadiness() {
        TaskGraph graph = chainOfTwo();
        int a = graph.indexOf("A");
        int b = graph.indexOf("B");
        SequentialAlgorithm search = new SequentialAlgorithm(graph, 1);

        // Schedule both tasks, then backtrack one step: B should be the ready task again.
        search.place(a, 0);
        search.place(b, 0);
        search.undo();
        assertEquals(b, search.nextReadyTask());

        // Backtrack the other step too: we are back at the start, with only A ready.
        search.undo();
        assertEquals(a, search.nextReadyTask());
    }
}
