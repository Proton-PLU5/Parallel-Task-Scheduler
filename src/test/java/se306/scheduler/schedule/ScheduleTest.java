package se306.scheduler.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;

/**
 * Tests {@link Schedule}'s constructor checks. A schedule that misses a task, puts one on a
 * processor that does not exist, or starts one before time 0 must be rejected here, rather than
 * being carried silently through to the output file.
 *
 * <p>Every test uses the same tiny graph: two independent tasks, A taking 2 time units and B
 * taking 3. A is task index 0 and B is task index 1, so each {@code new int[] {x, y}} below reads
 * as "x for A, y for B".
 */
class ScheduleTest {

    private static TaskGraph twoTasks() {
        GraphBuilder builder = new GraphBuilder();
        builder.addNode("A", 2);
        builder.addNode("B", 3);
        return builder.build();
    }

    @Test
    @DisplayName("a start-time list with the wrong number of entries is rejected")
    void wrongStartTimeLengthRejected() {
        TaskGraph graph = twoTasks();

        // Two tasks, but only one start time given.
        int[] startTimes = new int[] {0};
        int[] processors = new int[] {0, 0};

        assertThrows(IllegalArgumentException.class,
                () -> new Schedule(graph, startTimes, processors, 1));
    }

    @Test
    @DisplayName("a processor list with the wrong number of entries is rejected")
    void wrongProcessorLengthRejected() {
        TaskGraph graph = twoTasks();

        // Two tasks, but three processor assignments given.
        int[] startTimes = new int[] {0, 0};
        int[] processors = new int[] {0, 0, 0};

        assertThrows(IllegalArgumentException.class,
                () -> new Schedule(graph, startTimes, processors, 1));
    }

    @Test
    @DisplayName("a task placed on processor -1 is rejected")
    void negativeProcessorRejected() {
        TaskGraph graph = twoTasks();

        int[] startTimes = new int[] {0, 0};
        int[] processors = new int[] {-1, 0};

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Schedule(graph, startTimes, processors, 2));
        assertTrue(error.getMessage().contains("invalid processor"), error.getMessage());
    }

    @Test
    @DisplayName("a task placed on processor 2 of a 2-processor schedule is rejected")
    void processorBeyondCountRejected() {
        TaskGraph graph = twoTasks();

        // Processors are numbered 0 and 1 here, so processor 2 does not exist.
        int[] startTimes = new int[] {0, 0};
        int[] processors = new int[] {0, 2};

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Schedule(graph, startTimes, processors, 2));
        assertTrue(error.getMessage().contains("invalid processor"), error.getMessage());
    }

    @Test
    @DisplayName("a task starting before time 0 is rejected")
    void negativeStartTimeRejected() {
        TaskGraph graph = twoTasks();

        int[] startTimes = new int[] {0, -5};
        int[] processors = new int[] {0, 1};

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Schedule(graph, startTimes, processors, 2));
        assertTrue(error.getMessage().contains("negative start time"), error.getMessage());
    }

    @Test
    @DisplayName("the makespan is when the last task finishes")
    void makespanIsLatestFinish() {
        TaskGraph graph = twoTasks();

        // A(2) runs from 0 to 2 on processor 0; B(3) runs from 4 to 7 on processor 1.
        Schedule schedule = new Schedule(graph, new int[] {0, 4}, new int[] {0, 1}, 2);

        assertEquals(7, schedule.makespan());
        assertEquals(2, schedule.taskCount());
        assertEquals(2, schedule.numProcessors());
    }

    @Test
    @DisplayName("toString summarises the schedule in one line")
    void toStringSummarisesTheSchedule() {
        TaskGraph graph = twoTasks();

        // A(2) runs from 0 to 2, then B(3) runs from 2 to 5, all on one processor.
        Schedule schedule = new Schedule(graph, new int[] {0, 2}, new int[] {0, 0}, 1);

        assertEquals("Schedule[2 tasks, 1 processors, makespan=5]", schedule.toString());
    }
}
