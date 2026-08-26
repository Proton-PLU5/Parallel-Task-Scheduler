package se306.scheduler.algorithm;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se306.scheduler.algorithm.parallel.ParallelAlgorithm;
import se306.scheduler.algorithm.sequential.SequentialAlgorithm;
import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.gui.SearchListener;
import se306.scheduler.schedule.Schedule;

/**
 * Tests the listener-taking constructors of both algorithms — the path the {@code -v} flag uses,
 * where the GUI is told about every new best schedule the search finds while it runs.
 */
class AlgorithmListenerTest {

    /**
     * A stand-in for the GUI: it implements {@link SearchListener} and simply remembers every
     * schedule it is told about. The method is synchronized because the parallel search can
     * announce schedules from several threads at once.
     */
    private static class RecordingListener implements SearchListener {
        final List<Schedule> announced = new ArrayList<>();

        @Override
        public synchronized void onNewBestSchedule(TaskGraph graph, Schedule schedule) {
            announced.add(schedule);
        }
    }

    /**
     * A chain of four tasks, A(4) then B(5) then C(2) then D(7). A chain can never run in
     * parallel, so the best possible makespan is always the plain sum of the weights: 18.
     */
    private static TaskGraph chain() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 4);
        builder.addNode("B", 5);
        builder.addNode("C", 2);
        builder.addNode("D", 7);
        builder.addEdge("A", "B", 10);
        builder.addEdge("B", "C", 10);
        builder.addEdge("C", "D", 10);
        return builder.build();
    }

    @Test
    @DisplayName("the sequential search announces its improving schedules, ending on the answer")
    void sequentialSearchAnnouncesBestSchedules() {
        TaskGraph graph = chain();
        RecordingListener listener = new RecordingListener();

        Schedule result = new SequentialAlgorithm(graph, 2, listener).solve();

        assertEquals(18, result.makespan());
        assertFalse(listener.announced.isEmpty(),
                "the listener must hear about at least the final best schedule");

        Schedule lastAnnounced = listener.announced.get(listener.announced.size() - 1);
        assertSame(result, lastAnnounced,
                "the last schedule announced should be the one solve() returns");
    }

    @Test
    @DisplayName("the parallel search announces its improving schedules, ending on the answer")
    void parallelSearchAnnouncesBestSchedules() {
        TaskGraph graph = chain();
        RecordingListener listener = new RecordingListener();

        Schedule result = new ParallelAlgorithm(graph, 2, 2, listener).solve();

        assertEquals(18, result.makespan());
        assertFalse(listener.announced.isEmpty(),
                "the listener must hear about at least the final best schedule");

        Schedule lastAnnounced = listener.announced.get(listener.announced.size() - 1);
        assertSame(result, lastAnnounced,
                "the last schedule announced should be the one solve() returns");
    }
}
