package se306.scheduler.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se306.scheduler.algorithm.SequentialAlgorithm;
import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SequentialAlgorithm} (Milestone 1: a valid, not necessarily optimal, schedule).
 *
 * <p>Small graphs get hand-traced tests with exact expected start times/processors. Larger
 * graphs use {@link #assertScheduleRespectsConstraints}, which checks no overlap per processor
 * and precedence + communication cost per edge, without needing exact values.
 *
 * <p>Graphs are built with {@link GraphBuilder}, not parsed from {@code .dot} files.
 */

class DFSBranchAndBoundTest {

    // ---------------------------------------------------------------------------------------
    // Trivial cases
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a single task starts at time 0 on the only processor")
    void singleTaskSingleProcessor() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 5);
        TaskGraph g = gb.build();

        Schedule s = new SequentialAlgorithm(g, 1).solve();

        assertEquals(0, s.startTime(g.indexOf("A")));
        assertEquals(0, s.processor(g.indexOf("A")));
        assertEquals(5, s.makespan());
    }

    @Test
    @DisplayName("a single task is placed on processor 0 even when more are available")
    void singleTaskMultipleProcessorsUsesFirstProcessor() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 5);
        TaskGraph g = gb.build();

        Schedule s = new SequentialAlgorithm(g, 4).solve();

        assertEquals(0, s.startTime(g.indexOf("A")));
        assertEquals(0, s.processor(g.indexOf("A")));
        assertEquals(5, s.makespan());
    }

    @Test
    @DisplayName("numProcessors must be at least 1")
    void rejectsInvalidProcessorCount() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 1);
        TaskGraph g = gb.build();

        assertThrows(IllegalArgumentException.class, () -> new SequentialAlgorithm(g, 0));
        assertThrows(IllegalArgumentException.class, () -> new SequentialAlgorithm(g, -1));
    }

    // ---------------------------------------------------------------------------------------
    // Independent tasks: pure load balancing, no precedence/communication in play
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("two independent tasks run in parallel when two processors are available")
    void twoIndependentTasksRunInParallel() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 4);
        gb.addNode("B", 6);
        TaskGraph g = gb.build();
        int a = g.indexOf("A");
        int b = g.indexOf("B");

        Schedule s = new SequentialAlgorithm(g, 2).solve();

        // Both tasks are ready at t=0. The scheduler examines processors in index order,
        // and tasks are considered in topological/declaration order. Therefore:
        // - A is scheduled first and takes processor 0 at t=0.
        // - When scheduling B, processor 0 is busy until t=4 while processor 1 is free at t=0,
        //   so B is placed on processor 1 at t=0.
        assertEquals(0, s.startTime(a));
        assertEquals(0, s.processor(a));
        assertEquals(0, s.startTime(b));
        assertEquals(1, s.processor(b));
        assertEquals(6, s.makespan());
    }

    @Test
    @DisplayName("two independent tasks are serialised on a single processor")
    void twoIndependentTasksOneProcessorRunSequentially() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 4);
        gb.addNode("B", 6);
        TaskGraph g = gb.build();
        int a = g.indexOf("A");
        int b = g.indexOf("B");

        Schedule s = new SequentialAlgorithm(g, 1).solve();

        assertEquals(0, s.startTime(a));
        assertEquals(4, s.startTime(b));
        assertEquals(0, s.processor(a));
        assertEquals(0, s.processor(b));
        assertEquals(10, s.makespan());
    }


    // ---------------------------------------------------------------------------------------
    // Precedence constraints
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a chain of dependent tasks is fully serialised on one processor")
    void chainRespectsPrecedenceOnOneProcessor() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 2);
        gb.addNode("B", 3);
        gb.addNode("C", 4);
        gb.addEdge("A", "B", 10); // communication cost is irrelevant on a single processor
        gb.addEdge("B", "C", 10);
        TaskGraph g = gb.build();
        int a = g.indexOf("A");
        int b = g.indexOf("B");
        int c = g.indexOf("C");

        Schedule s = new SequentialAlgorithm(g, 1).solve();

        assertEquals(0, s.startTime(a));
        assertEquals(2, s.startTime(b));
        assertEquals(5, s.startTime(c));
        assertEquals(9, s.makespan());
        assertScheduleRespectsConstraints(g, s);
    }

    // ---------------------------------------------------------------------------------------
    // Communication cost trade-offs
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a high communication cost keeps a dependent task on the same processor")
    void expensiveCommunicationKeepsDependentTaskTogether() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 10);
        gb.addNode("B", 1);
        gb.addEdge("A", "B", 100);
        TaskGraph g = gb.build();
        int a = g.indexOf("A");
        int b = g.indexOf("B");

        Schedule s = new SequentialAlgorithm(g, 2).solve();

        // Compare earliest-start-time for B on each processor:
        // - On P0 (same as A): ready = max(free[0]=10, finish(A)+0) = 10.
        // - On P1 (different): ready = max(free[1]=0, finish(A)+comm=10+100) = 110.
        // Since 10 < 110 the scheduler places B on P0 to avoid the expensive communication.
        assertEquals(0, s.processor(a));
        assertEquals(0, s.processor(b));
        assertEquals(10, s.startTime(b));
        assertEquals(11, s.makespan());
        assertScheduleRespectsConstraints(g, s);
    }

    @Test
    @DisplayName("cheap communication lets a fork's branches run on different processors")
    void cheapCommunicationAllowsForkToParallelise() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 2);
        gb.addNode("B", 5);
        gb.addNode("C", 5);
        gb.addEdge("A", "B", 1);
        gb.addEdge("A", "C", 1);
        TaskGraph g = gb.build();
        int a = g.indexOf("A");
        int b = g.indexOf("B");
        int c = g.indexOf("C");

        Schedule s = new SequentialAlgorithm(g, 2).solve();

        // Scheduling step-by-step:
        // - A scheduled on P0 at t=0 (P0 free until t=2).
        // - B: P0 would be ready at t=2, P1 at t=3 → B placed on P0 at t=2.
        // - C: after B, P0 would be ready at t=7 while P1 is ready at t=3 → C placed on P1 at t=3.
        assertEquals(0, s.processor(a));
        assertEquals(0, s.startTime(a));
        assertEquals(0, s.processor(b));
        assertEquals(2, s.startTime(b));
        assertEquals(1, s.processor(c));
        assertEquals(3, s.startTime(c));
        assertEquals(8, s.makespan());
        assertScheduleRespectsConstraints(g, s);
    }

    // ---------------------------------------------------------------------------------------
    // Larger graphs: invariants only (exact greedy output is not worth hand-tracing here,
    // and asserting on it would over-specify a heuristic that is explicitly allowed to be
    // suboptimal at Milestone 1 — what must hold is that the output is a *valid* schedule).
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("diamond graph (the project's own example) produces a valid schedule on 1-3 processors")
    void diamondGraphProducesValidSchedule() {
        // Same shape/weights as example.dot / Figure 1 of the project description.
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 2);
        gb.addNode("B", 3);
        gb.addNode("C", 6);
        gb.addNode("D", 9);
        gb.addEdge("A", "B", 3);
        gb.addEdge("A", "C", 3);
        gb.addEdge("B", "D", 2);
        gb.addEdge("C", "D", 8);
        TaskGraph g = gb.build();

        for (int p = 1; p <= 3; p++) {
            Schedule s = new SequentialAlgorithm(g, p).solve();
            assertScheduleRespectsConstraints(g, s);
            assertTrue(s.makespan() >= g.weight(g.indexOf("A")) + g.weight(g.indexOf("C"))
                            + g.weight(g.indexOf("D")),
                    "makespan can never beat the longest chain of task weights alone");
        }
    }

    @Test
    @DisplayName("a wide fan-out/fan-in graph produces a valid schedule on several processor counts")
    void wideForkJoinGraphProducesValidSchedule() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("Root", 3);
        String[] middle = {"M1", "M2", "M3", "M4", "M5"};
        for (String m : middle) {
            gb.addNode(m, 4);
            gb.addEdge("Root", m, 2);
        }
        gb.addNode("Sink", 5);
        for (String m : middle) {
            gb.addEdge(m, "Sink", 1);
        }
        TaskGraph g = gb.build();

        for (int p = 1; p <= 4; p++) {
            Schedule s = new SequentialAlgorithm(g, p).solve();
            assertScheduleRespectsConstraints(g, s);
        }
    }

    @Test
    @DisplayName("more processors than tasks still produces a valid schedule")
    void moreProcessorsThanTasksStillValid() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 3);
        gb.addNode("B", 4);
        gb.addEdge("A", "B", 5);
        TaskGraph g = gb.build();

        Schedule s = new SequentialAlgorithm(g, 8).solve();

        assertScheduleRespectsConstraints(g, s);
        // With only one real dependency, extra processors can't help this chain.
        assertEquals(7, s.makespan());
    }

    @Test
    @DisplayName("an empty graph produces an empty schedule")
    void emptyGraphProducesEmptySchedule() {
        GraphBuilder gb = new GraphBuilder();
        TaskGraph g = gb.build();

        Schedule s = new SequentialAlgorithm(g, 1).solve();

        assertEquals(0, s.taskCount());
        assertEquals(0, s.makespan());
    }

    @Test
    @DisplayName("three-or-more processor tie breaks to the lowest-index processor")
    void threeOrMoreProcessorTieBreaksToLowestIndex() {
        GraphBuilder gb = new GraphBuilder();
        // Create three independent unit tasks so they occupy processors 0..2 at t=0
        gb.addNode("A", 1);
        gb.addNode("B", 1);
        gb.addNode("C", 1);
        // D depends on A, B, C with zero communication cost — all processors become
        // ready for D at the same earliest time, forcing a multi-way tie.
        gb.addNode("D", 2);
        gb.addEdge("A", "D", 0);
        gb.addEdge("B", "D", 0);
        gb.addEdge("C", "D", 0);
        TaskGraph g = gb.build();

        Schedule s = new SequentialAlgorithm(g, 4).solve();

        // A, B, C should be placed on processors 0,1,2 respectively (declaration order)
        assertEquals(0, s.processor(g.indexOf("A")));
        assertEquals(1, s.processor(g.indexOf("B")));
        assertEquals(2, s.processor(g.indexOf("C")));

        // D will see the same earliest start time on processors 0..3; tie-breaker
        // picks the lowest index (0). D should start at t=1 and finish at t=3.
        assertEquals(0, s.processor(g.indexOf("D")));
        assertEquals(1, s.startTime(g.indexOf("D")));
        assertEquals(3, s.makespan());
        assertScheduleRespectsConstraints(g, s);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Verifies the schedule meets the two fundamental constraints from the project spec (section
     * 3.1):
     * <ul>
     *   <li><b>Processor constraint</b> — no two tasks assigned to the same processor overlap in time.
     *   <li><b>Precedence + communication constraint</b> — for every edge {@code i -> j}, task
     *       {@code j} must start no earlier than task {@code i}'s finish time plus the communication
     *       cost (the communication cost is treated as 0 when both tasks share a processor).
     * </ul>
     *
     * The method also checks basic sanity of the schedule (non-negative start times and valid
     * processor indices) before evaluating the constraints.
     */
    private static void assertScheduleRespectsConstraints(TaskGraph g, Schedule s) {
        int n = g.taskCount();

        for (int t = 0; t < n; t++) {
            assertTrue(s.processor(t) >= 0 && s.processor(t) < s.numProcessors(),
                    "task '" + g.name(t) + "' has an out-of-range processor");
            assertTrue(s.startTime(t) >= 0, "task '" + g.name(t) + "' has a negative start time");
        }

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (s.processor(i) != s.processor(j)) {
                    continue;
                }
                int iEnd = s.startTime(i) + g.weight(i);
                int jEnd = s.startTime(j) + g.weight(j);
                boolean disjoint = iEnd <= s.startTime(j) || jEnd <= s.startTime(i);
                assertTrue(disjoint, "tasks '" + g.name(i) + "' and '" + g.name(j)
                        + "' overlap on processor " + s.processor(i));
            }
        }

        for (int from = 0; from < n; from++) {
            for (int k = g.childStart(from); k < g.childEnd(from); k++) {
                int to = g.childAt(k);
                int comm = s.processor(from) == s.processor(to) ? 0 : g.commCost(from, to);
                int earliestStart = s.startTime(from) + g.weight(from) + comm;
                assertTrue(s.startTime(to) >= earliestStart,
                        "edge '" + g.name(from) + " -> " + g.name(to) + "' violated: '"
                                + g.name(to) + "' starts at " + s.startTime(to) + " but needed "
                                + earliestStart);
            }
        }
    }
}