package se306.scheduler.schedule;

import org.junit.jupiter.api.Test;
import se306.scheduler.algorithm.SequentialAlgorithm;
import se306.scheduler.algorithm.parallel.ParallelAlgorithm;
import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;

import static org.junit.jupiter.api.Assertions.*;

public class AlgorithmTest {

    private TaskGraph diamondSmallCommCost() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 4);
        gb.addNode("B", 5);
        gb.addNode("C", 5);
        gb.addNode("D", 3);
        gb.addEdge("A", "B", 1);
        gb.addEdge("A", "C", 1);
        gb.addEdge("B", "D", 1);
        gb.addEdge("C", "D", 1);
        return gb.build();
    }

    private TaskGraph diamondLargeCommCost() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 2);
        gb.addNode("B", 3);
        gb.addNode("C", 6);
        gb.addNode("D", 9);
        gb.addEdge("A", "B", 3);
        gb.addEdge("A", "C", 3);
        gb.addEdge("B", "D", 2);
        gb.addEdge("C", "D", 8);
        return gb.build();
    }

    @Test
    void diamondSmallCommCost_agreesAcrossAlgorithms_twoProcessors() {
        assertAlgorithmsAgree(diamondSmallCommCost(), 2);
    }

    @Test
    void diamondSmallCommCost_agreesAcrossAlgorithms_fourProcessors() {
        assertAlgorithmsAgree(diamondSmallCommCost(), 4);
    }

    @Test
    void diamondLargeCommCost_agreesAcrossAlgorithms_twoProcessors() {
        assertAlgorithmsAgree(diamondLargeCommCost(), 2);
    }

    @Test
    void diamondLargeCommCost_agreesAcrossAlgorithms_fourProcessors() {
        assertAlgorithmsAgree(diamondLargeCommCost(), 4);
    }

    @Test
    void linearChain_optimalMakespanIsSumOfWeights_regardlessOfProcessorCount() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 4);
        gb.addNode("B", 5);
        gb.addNode("C", 2);
        gb.addNode("D", 7);
        gb.addEdge("A", "B", 10); // deliberately high comm costs: should never matter
        gb.addEdge("B", "C", 10);
        gb.addEdge("C", "D", 10);
        TaskGraph chain = gb.build();

        int expectedMakespan = 4 + 5 + 2 + 7; // 18

        for (int numProcessors : new int[] {1, 2, 4}) {
            Schedule sequential = new SequentialAlgorithm(chain, numProcessors).solve();
            Schedule parallel = new ParallelAlgorithm(chain, numProcessors, 4).solve();

            assertValidSchedule(chain, sequential);
            assertValidSchedule(chain, parallel);

            assertEquals(expectedMakespan, sequential.makespan(),
                    "Sequential: wrong makespan with " + numProcessors + " processors");
            assertEquals(expectedMakespan, parallel.makespan(),
                    "Parallel: wrong makespan with " + numProcessors + " processors");
        }
    }

    /**
     * Single task, single processor: trivial base case.
     */
    @Test
    void singleTask_makespanEqualsItsWeight() {
        GraphBuilder gb = new GraphBuilder();
        gb.addNode("A", 7);
        TaskGraph single = gb.build();

        Schedule sequential = new SequentialAlgorithm(single, 1).solve();
        Schedule parallel = new ParallelAlgorithm(single, 1, 2).solve();

        assertEquals(7, sequential.makespan());
        assertEquals(7, parallel.makespan());
    }

    // ---- edge case: invalid processor count -----------------------------

    @Test
    void constructors_rejectZeroOrNegativeProcessors() {
        TaskGraph graph = diamondSmallCommCost();

        assertThrows(IllegalArgumentException.class, () -> new SequentialAlgorithm(graph, 0));
        assertThrows(IllegalArgumentException.class, () -> new ParallelAlgorithm(graph, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new SequentialAlgorithm(graph, -1));
    }

    private void assertAlgorithmsAgree(TaskGraph graph, int numProcessors) {
        Schedule sequential = new SequentialAlgorithm(graph, numProcessors).solve();
        Schedule parallel = new ParallelAlgorithm(graph, numProcessors, 4).solve();

        assertValidSchedule(graph, sequential);
        assertValidSchedule(graph, parallel);

        assertEquals(
                sequential.makespan(), parallel.makespan(),
                "Sequential and parallel algorithms disagree on optimal makespan"
        );
    }

    private void assertValidSchedule(TaskGraph graph, Schedule schedule) {
        assertNotNull(schedule);
        assertEquals(graph.taskCount(), schedule.taskCount());
        assertScheduleRespectsConstraints(graph, schedule);
    }

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
