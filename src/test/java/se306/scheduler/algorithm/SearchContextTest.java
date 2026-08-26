package se306.scheduler.algorithm;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se306.scheduler.algorithm.core.SearchContext;
import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.gui.SearchListener;
import se306.scheduler.schedule.Schedule;

/**
 * Tests {@link SearchContext}: the bottom-level estimates it precomputes, the "best schedule so
 * far" bookkeeping shared between search threads, and the listener hook the GUI uses to watch the
 * search improve.
 */
class SearchContextTest {

    /**
     * A stand-in for the GUI: it implements {@link SearchListener} and simply remembers every
     * schedule it is told about, so tests can check what the search announced and in what order.
     */
    private static class RecordingListener implements SearchListener {
        final List<Schedule> announced = new ArrayList<>();

        @Override
        public void onNewBestSchedule(TaskGraph graph, Schedule schedule) {
            announced.add(schedule);
        }
    }

    /** Two independent tasks: A takes 2 time units, B takes 3. A is index 0, B is index 1. */
    private static TaskGraph twoTasks() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 2);
        builder.addNode("B", 3);
        return builder.build();
    }

    /** The diamond from the algorithm tests: A(4) fans out to B(5) and C(5), which join at D(3). */
    private static TaskGraph diamond() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 4);
        builder.addNode("B", 5);
        builder.addNode("C", 5);
        builder.addNode("D", 3);
        builder.addEdge("A", "B", 1);
        builder.addEdge("A", "C", 1);
        builder.addEdge("B", "D", 1);
        builder.addEdge("C", "D", 1);
        return builder.build();
    }

    @Test
    @DisplayName("both constructors reject a processor count below 1")
    void rejectsProcessorCountBelowOne() {
        TaskGraph graph = twoTasks();

        assertThrows(IllegalArgumentException.class,
                () -> new SearchContext(graph, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchContext(graph, 0, new RecordingListener()));
    }

    @Test
    @DisplayName("a task's bottom level is the longest chain of work from it to the end")
    void bottomLevelIsLongestRemainingChain() {
        TaskGraph graph = diamond();

        SearchContext context = new SearchContext(graph, 2);

        assertEquals(3, context.getBottomLevel(graph.indexOf("D")), "D(3) is the last task");
        assertEquals(8, context.getBottomLevel(graph.indexOf("B")), "B(5) then D(3)");
        assertEquals(8, context.getBottomLevel(graph.indexOf("C")), "C(5) then D(3)");
        assertEquals(12, context.getBottomLevel(graph.indexOf("A")), "A(4), B or C (5), D(3)");
    }

    @Test
    @DisplayName("a better schedule becomes the new best, and the listener is told about it")
    void betterScheduleReplacesBestAndNotifiesListener() {
        TaskGraph graph = twoTasks();
        RecordingListener listener = new RecordingListener();
        SearchContext context = new SearchContext(graph, 2, listener);

        assertEquals(Integer.MAX_VALUE, context.getBest(), "no schedule has been seen yet");

        // Offer a schedule finishing at time 3: A and B start together on separate processors.
        context.compareAndSetBestSchedule(3, new int[] {0, 0}, new int[] {0, 1});

        assertEquals(3, context.getBest());
        assertEquals(1, listener.announced.size(), "the listener hears about each new best");
        assertEquals(3, listener.announced.get(0).makespan());
        assertSame(context.getBestSchedule(), listener.announced.get(0),
                "the schedule announced is the one stored as best");
    }

    @Test
    @DisplayName("a schedule no better than the current best is ignored, and not announced")
    void worseScheduleIsIgnored() {
        TaskGraph graph = twoTasks();
        RecordingListener listener = new RecordingListener();
        SearchContext context = new SearchContext(graph, 2, listener);

        // First offer a schedule finishing at time 3, which becomes the best...
        context.compareAndSetBestSchedule(3, new int[] {0, 0}, new int[] {0, 1});
        Schedule bestSoFar = context.getBestSchedule();

        // ...then offer a worse one finishing at time 5: both tasks queued on one processor.
        context.compareAndSetBestSchedule(5, new int[] {0, 2}, new int[] {0, 0});

        assertEquals(3, context.getBest(), "a worse makespan must not replace the best");
        assertSame(bestSoFar, context.getBestSchedule(), "the best schedule is unchanged");
        assertEquals(1, listener.announced.size(), "worse schedules are not announced");
    }
}
