package se306.scheduler.schedule;

import se306.scheduler.algorithm.AbstractSearch;
import se306.scheduler.algorithm.SearchContext;
import se306.scheduler.algorithm.SequentialAlgorithm;
import se306.scheduler.algorithm.parallel.ParallelAlgorithm;
import se306.scheduler.algorithm.parallel.ParallelSearch;
import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the branch-and-bound search ({@link AbstractSearch} / {@link SequentialAlgorithm} /
 * {@link ParallelSearch}) always returns a truly optimal schedule, by cross-checking its results
 * against an independent brute-force reference solver.
 *
 * <p><b>Why the brute force enumerates task orderings, not just processor assignments:</b>
 * {@link AbstractSearch#nextReadyTask()} always selects the lowest-index ready task, so the search
 * only branches over processor choice for a fixed scheduling order. If there existed a graph where
 * the true optimum required scheduling ready tasks in a different relative order (e.g. because of
 * asymmetric communication costs), a fixed-order search could silently miss it. The reference
 * solver here enumerates <em>all</em> valid topological orders as well as all processor assignments,
 * so it is a genuine upper bound on the achievable optimum independent of the algorithm's internal
 * heuristics — an exhaustive reference implementation using the same scheduling semantics, but an
 * entirely independent search strategy.
 */
class BranchAndBoundOptimalityTest {

    // =====================================================================================
    // Graph construction helpers
    // =====================================================================================

    /** Builds a graph with tasks named "0".."n-1". edges: {from, to, commCost} triples. */
    private static TaskGraph graph(int[] weights, int[][] edges) {
        GraphBuilder gb = new GraphBuilder();
        for (int i = 0; i < weights.length; i++) {
            gb.addNode(Integer.toString(i), weights[i]);
        }
        for (int[] e : edges) {
            gb.addEdge(Integer.toString(e[0]), Integer.toString(e[1]), e[2]);
        }
        return gb.build();
    }

    /** Carries the generated graph alongside its raw weights and edges for failure diagnostics. */
    private record GeneratedGraph(TaskGraph graph, int[] weights, int[][] edges) {
        /** Returns a compact human-readable description suitable for use in assertion messages. */
        String describe(int procs) {
            StringBuilder sb = new StringBuilder();
            sb.append("procs=").append(procs)
              .append(", weights=").append(java.util.Arrays.toString(weights))
              .append(", edges=[");
            for (int i = 0; i < edges.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(edges[i][0]).append("->").append(edges[i][1])
                  .append("(c=").append(edges[i][2]).append(")");
            }
            sb.append("]");
            return sb.toString();
        }
    }

    private static GeneratedGraph randomDag(Random rnd, int n, double edgeProbability, int maxWeight, int maxComm) {
        int[] weights = new int[n];
        for (int i = 0; i < n; i++) {
            weights[i] = 1 + rnd.nextInt(maxWeight);
        }
        List<int[]> edges = new ArrayList<>();
        // Only allow i -> j for i < j: guarantees the result is acyclic.
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (rnd.nextDouble() < edgeProbability) {
                    edges.add(new int[]{i, j, rnd.nextInt(maxComm + 1)});
                }
            }
        }
        int[][] edgeArray = edges.toArray(new int[0][]);
        return new GeneratedGraph(graph(weights, edgeArray), weights, edgeArray);
    }

    // =====================================================================================
    // Brute-force reference solver (independent ground truth)
    // =====================================================================================

    /** Enumerates every topological ordering of the graph's tasks. */
    private static List<int[]> allTopologicalOrders(TaskGraph graph) {
        int n = graph.taskCount();
        int[] indegree = new int[n];
        for (int t = 0; t < n; t++) {
            indegree[t] = graph.parentCount(t);
        }
        boolean[] used = new boolean[n];
        int[] current = new int[n];
        List<int[]> results = new ArrayList<>();
        orderBacktrack(graph, indegree, used, current, 0, results);
        return results;
    }

    private static void orderBacktrack(TaskGraph graph, int[] indegree, boolean[] used, int[] current,
                                        int pos, List<int[]> results) {
        int n = current.length;
        if (pos == n) {
            results.add(current.clone());
            return;
        }
        for (int t = 0; t < n; t++) {
            if (!used[t] && indegree[t] == 0) {
                used[t] = true;
                current[pos] = t;
                List<Integer> decremented = new ArrayList<>();
                for (int k = graph.childStart(t); k < graph.childEnd(t); k++) {
                    int child = graph.childAt(k);
                    indegree[child]--;
                    decremented.add(child);
                }
                orderBacktrack(graph, indegree, used, current, pos + 1, results);
                for (int child : decremented) {
                    indegree[child]++;
                }
                used[t] = false;
            }
        }
    }

    /** Decodes {@code mask} into a per-task processor assignment in base {@code numProcessors}. */
    private static int[] decodeAssignment(int mask, int numProcessors, int n) {
        int[] procOf = new int[n];
        for (int i = 0; i < n; i++) {
            procOf[i] = mask % numProcessors;
            mask /= numProcessors;
        }
        return procOf;
    }

    /**
     * True optimal makespan found by exhaustively trying every valid task-scheduling order combined
     * with every possible processor assignment. Intentionally implemented with no code shared with
     * {@link AbstractSearch}, so a bug in the search cannot be mirrored here.
     */
    private static int bruteForceOptimalMakespan(TaskGraph graph, int numProcessors) {
        int n = graph.taskCount();
        List<int[]> orders = allTopologicalOrders(graph);
        int numAssignments = (int) Math.pow(numProcessors, n);

        int best = Integer.MAX_VALUE;
        for (int[] order : orders) {
            for (int mask = 0; mask < numAssignments; mask++) {
                int[] procOf = decodeAssignment(mask, numProcessors, n);
                int[] startTime = new int[n];
                int[] processorFreeAt = new int[numProcessors];
                int makespan = 0;

                for (int task : order) {
                    int proc = procOf[task];
                    int ready = processorFreeAt[proc];
                    for (int k = graph.parentStart(task); k < graph.parentEnd(task); k++) {
                        int parent = graph.parentAt(k);
                        int parentFinish = startTime[parent] + graph.weight(parent);
                        int comm = (procOf[parent] == proc) ? 0 : graph.commCost(parent, task);
                        ready = Math.max(ready, parentFinish + comm);
                    }
                    startTime[task] = ready;
                    processorFreeAt[proc] = ready + graph.weight(task);
                    makespan = Math.max(makespan, processorFreeAt[proc]);
                }
                best = Math.min(best, makespan);
            }
        }
        return best;
    }

    /**
     * Best achievable makespan when some tasks are already fixed (by {@code fixedProcOf} /
     * {@code fixedStartTime}) and the remaining tasks must still be assigned. Only the remaining
     * (unscheduled) tasks are enumerated; the already-scheduled tasks' timings are treated as
     * given input. This is the ground-truth completion cost used to validate lower-bound soundness.
     *
     * @param graph           the full task graph
     * @param numProcessors   number of processors
     * @param fixedProcOf     processor assignment for already-scheduled tasks (-1 = not yet scheduled)
     * @param fixedStartTime  start times for already-scheduled tasks (-1 = not yet scheduled)
     * @param processorFreeAt current processor finish times after the partial schedule
     */
    private static int bruteForceOptimalCompletion(
            TaskGraph graph,
            int numProcessors,
            int[] fixedProcOf,
            int[] fixedStartTime,
            int[] processorFreeAt) {

        int n = graph.taskCount();

        // Collect the indices of unscheduled tasks.
        List<Integer> remaining = new ArrayList<>();
        for (int t = 0; t < n; t++) {
            if (fixedProcOf[t] == -1) remaining.add(t);
        }
        int r = remaining.size();

        // Re-compute indegrees for the remaining subgraph: a remaining task's
        // indegree counts only parents that are also still unscheduled.
        int[] indegree = new int[n];
        for (int t : remaining) {
            for (int k = graph.parentStart(t); k < graph.parentEnd(t); k++) {
                int parent = graph.parentAt(k);
                if (fixedProcOf[parent] == -1) {
                    indegree[t]++;
                }
            }
        }

        // Enumerate all valid orderings of the remaining tasks.
        List<int[]> remainingOrders = new ArrayList<>();
        boolean[] used = new boolean[n];
        int[] cur = new int[r];
        remainingOrderBacktrack(graph, remaining, indegree, used, cur, 0, remainingOrders);

        int numAssignments = (int) Math.pow(numProcessors, r);
        int best = Integer.MAX_VALUE;

        for (int[] order : remainingOrders) {
            for (int mask = 0; mask < numAssignments; mask++) {
                // Decode processor assignment for remaining tasks only.
                int[] procOf = fixedProcOf.clone();
                int[] startTime = fixedStartTime.clone();
                int[] freeAt = processorFreeAt.clone();

                int tmp = mask;
                for (int i = 0; i < r; i++) {
                    procOf[order[i]] = tmp % numProcessors;
                    tmp /= numProcessors;
                }

                int makespan = 0;
                // Seed makespan from already-scheduled tasks.
                for (int t = 0; t < n; t++) {
                    if (fixedProcOf[t] != -1) {
                        makespan = Math.max(makespan, fixedStartTime[t] + graph.weight(t));
                    }
                }

                for (int task : order) {
                    int proc = procOf[task];
                    int ready = freeAt[proc];
                    for (int k = graph.parentStart(task); k < graph.parentEnd(task); k++) {
                        int parent = graph.parentAt(k);
                        int parentFinish = startTime[parent] + graph.weight(parent);
                        int comm = (procOf[parent] == proc) ? 0 : graph.commCost(parent, task);
                        ready = Math.max(ready, parentFinish + comm);
                    }
                    startTime[task] = ready;
                    freeAt[proc] = ready + graph.weight(task);
                    makespan = Math.max(makespan, freeAt[proc]);
                }
                best = Math.min(best, makespan);
            }
        }
        return best;
    }

    /**
     * Backtracking helper that enumerates topological orders of the {@code candidates} subset.
     * {@code indegree} counts only edges within that subset.
     */
    private static void remainingOrderBacktrack(
            TaskGraph graph,
            List<Integer> candidates,
            int[] indegree,
            boolean[] used,
            int[] current,
            int pos,
            List<int[]> results) {

        if (pos == candidates.size()) {
            results.add(current.clone());
            return;
        }
        for (int t : candidates) {
            if (!used[t] && indegree[t] == 0) {
                used[t] = true;
                current[pos] = t;
                List<Integer> decremented = new ArrayList<>();
                for (int k = graph.childStart(t); k < graph.childEnd(t); k++) {
                    int child = graph.childAt(k);
                    if (!used[child]) {
                        indegree[child]--;
                        decremented.add(child);
                    }
                }
                remainingOrderBacktrack(graph, candidates, indegree, used, current, pos + 1, results);
                for (int child : decremented) {
                    indegree[child]++;
                }
                used[t] = false;
            }
        }
    }

    // =====================================================================================
    // Schedule validity checks (independent of Schedule's own constructor validation)
    // =====================================================================================

    private static void assertPrecedenceRespected(TaskGraph graph, Schedule schedule) {
        for (int task = 0; task < graph.taskCount(); task++) {
            for (int k = graph.parentStart(task); k < graph.parentEnd(task); k++) {
                int parent = graph.parentAt(k);
                int parentFinish = schedule.startTime(parent) + graph.weight(parent);
                int comm = (schedule.processor(parent) == schedule.processor(task))
                        ? 0 : graph.commCost(parent, task);
                assertTrue(schedule.startTime(task) >= parentFinish + comm,
                        "task " + graph.name(task) + " starts before predecessor "
                                + graph.name(parent) + " finishes (+ comm cost)");
            }
        }
    }

    private static void assertNoProcessorOverlap(TaskGraph graph, Schedule schedule) {
        Map<Integer, List<Integer>> byProcessor = new HashMap<>();
        for (int task = 0; task < graph.taskCount(); task++) {
            byProcessor.computeIfAbsent(schedule.processor(task), p -> new ArrayList<>()).add(task);
        }
        for (List<Integer> tasks : byProcessor.values()) {
            tasks.sort((a, b) -> Integer.compare(schedule.startTime(a), schedule.startTime(b)));
            for (int i = 1; i < tasks.size(); i++) {
                int prev = tasks.get(i - 1);
                int curr = tasks.get(i);
                int prevFinish = schedule.startTime(prev) + graph.weight(prev);
                assertTrue(schedule.startTime(curr) >= prevFinish,
                        "tasks " + graph.name(prev) + " and " + graph.name(curr)
                                + " overlap on the same processor");
            }
        }
    }

    private static void assertValidSchedule(TaskGraph graph, Schedule schedule) {
        assertPrecedenceRespected(graph, schedule);
        assertNoProcessorOverlap(graph, schedule);
    }

    // =====================================================================================
    // Hand-computed optimum cases
    // =====================================================================================

    @Nested
    @DisplayName("Hand-computed optimal makespans")
    class HandComputedCases {

        @Test
        @DisplayName("Strict chain: no parallelism possible, makespan = sum of weights")
        void chainGraphHasNoParallelismOpportunity() {
            // 0 -> 1 -> 2 -> 3, weights 2,3,4,5. Comm cost irrelevant since the optimal
            // strategy is always to run the whole chain on one processor.
            TaskGraph g = graph(
                    new int[]{2, 3, 4, 5},
                    new int[][]{{0, 1, 7}, {1, 2, 7}, {2, 3, 7}});

            for (int procs : new int[]{1, 2, 3, 4}) {
                Schedule schedule = new SequentialAlgorithm(g, procs).solve();
                assertEquals(14, schedule.makespan(),
                        "chain makespan should equal the sum of weights regardless of processor count");
                assertEquals(bruteForceOptimalMakespan(g, procs), schedule.makespan());
                assertValidSchedule(g, schedule);
            }
        }

        @Test
        @DisplayName("Independent tasks balance evenly across processors")
        void independentTasksBalanceAcrossProcessors() {
            TaskGraph g = graph(new int[]{4, 4, 4, 4}, new int[][]{});

            Schedule schedule = new SequentialAlgorithm(g, 2).solve();
            assertEquals(8, schedule.makespan());
            assertEquals(bruteForceOptimalMakespan(g, 2), schedule.makespan());
            assertValidSchedule(g, schedule);
        }

        @Test
        @DisplayName("More processors than tasks: makespan = the largest single task weight")
        void moreProcessorsThanTasks() {
            TaskGraph g = graph(new int[]{5, 5, 5}, new int[][]{});

            Schedule schedule = new SequentialAlgorithm(g, 5).solve();
            assertEquals(5, schedule.makespan());
            assertEquals(bruteForceOptimalMakespan(g, 5), schedule.makespan());
            assertValidSchedule(g, schedule);
        }

        @Test
        @DisplayName("Single task graph")
        void singleTask() {
            TaskGraph g = graph(new int[]{7}, new int[][]{});
            for (int procs : new int[]{1, 2, 3}) {
                Schedule schedule = new SequentialAlgorithm(g, procs).solve();
                assertEquals(7, schedule.makespan());
                assertValidSchedule(g, schedule);
            }
        }

        @Test
        @DisplayName("Diamond fork-join with heavy comm costs forces single-processor execution")
        void diamondWithHeavyCommCostForcesColocation() {
            // 0 -> 1, 0 -> 2, 1 -> 3, 2 -> 3, all comm costs = 10 (far larger than any task weight),
            // so splitting across processors is always worse than running everything on one.
            TaskGraph g = graph(
                    new int[]{2, 3, 3, 2},
                    new int[][]{{0, 1, 10}, {0, 2, 10}, {1, 3, 10}, {2, 3, 10}});

            Schedule schedule = new SequentialAlgorithm(g, 2).solve();
            assertEquals(10, schedule.makespan(),
                    "with dominating comm costs, running everything on one processor (2+3+3+2) should win");
            assertEquals(bruteForceOptimalMakespan(g, 2), schedule.makespan());
            assertValidSchedule(g, schedule);
        }

        @Test
        @DisplayName("Diamond fork-join with zero comm costs allows true parallel branches")
        void diamondWithZeroCommCostAllowsParallelism() {
            // Same shape, but free communication: 1 and 2 can run in parallel on separate
            // processors without penalty, so the critical path (0 -> 1 -> 3, both weight 3) wins.
            TaskGraph g = graph(
                    new int[]{2, 3, 3, 2},
                    new int[][]{{0, 1, 0}, {0, 2, 0}, {1, 3, 0}, {2, 3, 0}});

            Schedule schedule = new SequentialAlgorithm(g, 2).solve();
            assertEquals(7, schedule.makespan(), "critical path 0->1->3 (or 0->2->3) = 2+3+2 = 7");
            assertEquals(bruteForceOptimalMakespan(g, 2), schedule.makespan());
            assertValidSchedule(g, schedule);
        }

        @Test
        @DisplayName("Figure 1 from the project spec matches the true brute-force optimum")
        void specFigure1ExampleMatchesBruteForceOptimum() {
            // The project's own canonical example graph: a=2, b=3, c=3, d=2, with
            // a->b:1, a->c:2, b->d:2, c->d:1 (task indices 0=a, 1=b, 2=c, 3=d).
            TaskGraph g = graph(
                    new int[]{2, 3, 3, 2},
                    new int[][]{{0, 1, 1}, {0, 2, 2}, {1, 3, 2}, {2, 3, 1}});

            for (int procs : new int[]{1, 2, 3}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule schedule = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, schedule.makespan(),
                        "procs=" + procs + " — sequential result is not the true optimum "
                                + "for the spec's own Figure 1 example");
                assertValidSchedule(g, schedule);
            }
        }

        @Test
        @DisplayName("Project spec diamond (small comm cost) matches the true brute-force optimum")
        void diamondSmallCommCostMatchesBruteForceOptimum() {
            TaskGraph g = graph(
                    new int[]{4, 5, 5, 3},
                    new int[][]{{0, 1, 1}, {0, 2, 1}, {1, 3, 1}, {2, 3, 1}});

            for (int procs : new int[]{2, 4}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule schedule = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, schedule.makespan(),
                        "procs=" + procs + " — sequential result is not the true optimum");
                assertValidSchedule(g, schedule);
            }
        }

        @Test
        @DisplayName("Project spec diamond (large comm cost) matches the true brute-force optimum")
        void diamondLargeCommCostMatchesBruteForceOptimum() {
            TaskGraph g = graph(
                    new int[]{2, 3, 6, 9},
                    new int[][]{{0, 1, 3}, {0, 2, 3}, {1, 3, 2}, {2, 3, 8}});

            for (int procs : new int[]{2, 4}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule schedule = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, schedule.makespan(),
                        "procs=" + procs + " — sequential result is not the true optimum");
                assertValidSchedule(g, schedule);
            }
        }

        @Test
        @DisplayName("Zero-weight tasks and zero comm costs don't break anything")
        void zeroWeightTasksAreHandledCorrectly() {
            TaskGraph g = graph(
                    new int[]{0, 5, 0, 5},
                    new int[][]{{0, 1, 0}, {1, 2, 0}, {2, 3, 0}});
            Schedule schedule = new SequentialAlgorithm(g, 2).solve();
            assertEquals(10, schedule.makespan());
            assertEquals(bruteForceOptimalMakespan(g, 2), schedule.makespan());
            assertValidSchedule(g, schedule);
        }
    }

    // =====================================================================================
    // Adversarial graphs targeting known B&B failure modes
    // =====================================================================================

    @Nested
    @DisplayName("Adversarial graphs targeting known branch-and-bound failure modes")
    class AdversarialCases {

        /**
         * Two independent ready tasks with very different weights (A=1, B=20) sharing a single
         * join task C. The fixed {@code nextReadyTask()} ordering always picks A first. If the
         * algorithm cannot recover the optimal schedule when the heavier task runs first, it will
         * miss the optimum here.
         *
         * <pre>
         *   A(1) ──┐
         *          ├──► C(1)
         *   B(20)──┘
         * </pre>
         */
        @Test
        @DisplayName("Asymmetric ready tasks: heavy sibling must not block the critical path")
        void asymmetricReadyTasksWithSharedJoin() {
            TaskGraph g = graph(
                    new int[]{1, 20, 1},
                    new int[][]{{0, 2, 0}, {1, 2, 0}});

            for (int procs : new int[]{1, 2, 3}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule actual = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, actual.makespan(),
                        "procs=" + procs + " — heavy/light sibling pair not solved optimally");
                assertValidSchedule(g, actual);
            }
        }

        /**
         * Fork-join where communication costs from the source to each branch are equal, but the
         * costs from each branch into the sink differ greatly (B→D=1, C→D=15). This asymmetry
         * means scheduling D with B (on the same processor) is very different from scheduling D
         * with C, and naively picking task order can miss the optimum.
         *
         * <pre>
         *        ┌── B(3) ──(1)──┐
         *   A(2)─┤               ├──► D(2)
         *        └── C(3) ─(15)──┘
         * </pre>
         */
        @Test
        @DisplayName("Asymmetric outgoing comm costs: sink placement is highly sensitive")
        void asymmetricOutgoingCommCostsOnJoin() {
            TaskGraph g = graph(
                    new int[]{2, 3, 3, 2},
                    new int[][]{{0, 1, 5}, {0, 2, 5}, {1, 3, 1}, {2, 3, 15}});

            for (int procs : new int[]{2, 3}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule actual = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, actual.makespan(),
                        "procs=" + procs + " — asymmetric outgoing comm costs not solved optimally");
                assertValidSchedule(g, actual);
            }
        }

        /**
         * Fork-join where communication costs from the source differ per branch (A→B=2, A→C=10).
         * Moving B and C to a second processor incurs very different penalties, so the optimal
         * processor assignment is non-obvious.
         *
         * <pre>
         *        ─(2)──► B(3) ──┐
         *   A(2)─               ├──► D(2)
         *        ─(10)─► C(3) ──┘
         * </pre>
         */
        @Test
        @DisplayName("Asymmetric incoming comm costs on branches: fork placement is highly sensitive")
        void asymmetricIncomingCommCostsOnFork() {
            TaskGraph g = graph(
                    new int[]{2, 3, 3, 2},
                    new int[][]{{0, 1, 2}, {0, 2, 10}, {1, 3, 5}, {2, 3, 5}});

            for (int procs : new int[]{2, 3}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule actual = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, actual.makespan(),
                        "procs=" + procs + " — asymmetric incoming comm costs not solved optimally");
                assertValidSchedule(g, actual);
            }
        }

        /**
         * Two fully independent parallel branches that each end at a shared sink, with moderate
         * communication costs. Tests that the scheduler can balance two independent pipelines
         * across processors without letting one starve.
         *
         * <pre>
         *   A(3) ──► B(4) ──(2)──┐
         *                         ├──► E(3)
         *   C(5) ──► D(2) ──(2)──┘
         * </pre>
         */
        @Test
        @DisplayName("Two independent branches merging at a single sink")
        void twoIndependentBranchesWithSharedSink() {
            TaskGraph g = graph(
                    new int[]{3, 4, 5, 2, 3},
                    new int[][]{{0, 1, 0}, {2, 3, 0}, {1, 4, 2}, {3, 4, 2}});

            for (int procs : new int[]{1, 2, 3}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule actual = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, actual.makespan(),
                        "procs=" + procs + " — two-branch pipeline not solved optimally");
                assertValidSchedule(g, actual);
            }
        }

        /**
         * Three fully independent tasks with no dependencies at all. The optimal schedule on
         * N processors simply bins them as evenly as possible; on 1 processor it runs them all
         * serially.
         */
        @Test
        @DisplayName("Fully independent tasks across a range of processor counts")
        void fullyIndependentTasksVariousProcessorCounts() {
            // Weights chosen so that on 3 procs the optimum is 5, on 2 it's 9, on 1 it's 14.
            TaskGraph g = graph(new int[]{5, 4, 5}, new int[][]{});

            for (int procs : new int[]{1, 2, 3, 4, 5}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule actual = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, actual.makespan(),
                        "procs=" + procs + " — independent tasks not balanced optimally");
                assertValidSchedule(g, actual);
            }
        }
    }

    // =====================================================================================
    // Cross-check against brute force on randomized small graphs
    // =====================================================================================

    @Nested
    @DisplayName("Randomized cross-checks against brute-force optimum")
    class RandomizedCrossChecks {

        /**
         * Validates the sequential algorithm against the brute-force reference on a wide variety
         * of random small DAGs. Processor counts range from 1 to 4 to catch processor-count bugs
         * at the extremes (single processor, more processors than tasks, etc.).
         */
        @Test
        @DisplayName("Sequential search matches brute force on many random small DAGs")
        void sequentialSearchMatchesBruteForceOnRandomGraphs() {
            Random rnd = new Random(42);
            int trials = 50;

            for (int trial = 0; trial < trials; trial++) {
                int n = 4 + rnd.nextInt(3);           // 4..6 tasks
                int procs = 1 + rnd.nextInt(4);        // 1..4 processors
                double edgeProbability = 0.3 + rnd.nextDouble() * 0.4;
                GeneratedGraph gen = randomDag(rnd, n, edgeProbability, 6, 5);
                TaskGraph g = gen.graph();

                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule actual = new SequentialAlgorithm(g, procs).solve();

                assertEquals(expected, actual.makespan(),
                        "trial " + trial + " — branch and bound did not find the true optimum\n  "
                                + gen.describe(procs));
                assertValidSchedule(g, actual);
            }
        }
    }

    // =====================================================================================
    // Sequential vs parallel agreement — both validated against brute force
    // =====================================================================================

    @Nested
    @DisplayName("Sequential and parallel search agree, both against brute force")
    class SequentialParallelAgreement {

        private int solveInParallel(TaskGraph graph, int numProcessors) {
            return new ParallelAlgorithm(graph, numProcessors, 4).solve().makespan();
        }

        /**
         * For every random graph, both the sequential and parallel algorithms are independently
         * compared to the brute-force optimum. This gives the stronger diamond-shaped oracle:
         *
         * <pre>
         *               ┌── Sequential ──┐
         * Brute Force ──┤                ├── same optimum
         *               └── Parallel ────┘
         * </pre>
         *
         * rather than only checking Sequential→Parallel agreement.
         */
        @Test
        @DisplayName("Sequential and parallel both match brute force on random DAGs")
        void parallelAndSequentialBothMatchBruteForceOnRandomGraphs() {
            Random rnd = new Random(7);
            int trials = 50;

            for (int trial = 0; trial < trials; trial++) {
                int n = 4 + rnd.nextInt(3);           // 4..6 tasks
                int procs = 1 + rnd.nextInt(4);        // 1..4 processors
                double edgeProbability = 0.3 + rnd.nextDouble() * 0.4;
                GeneratedGraph gen = randomDag(rnd, n, edgeProbability, 6, 5);
                TaskGraph g = gen.graph();

                int expected = bruteForceOptimalMakespan(g, procs);
                int sequentialResult = new SequentialAlgorithm(g, procs).solve().makespan();
                int parallelResult = solveInParallel(g, procs);

                assertEquals(expected, sequentialResult,
                        "trial " + trial + " — sequential did not find the true optimum\n  "
                                + gen.describe(procs));
                assertEquals(expected, parallelResult,
                        "trial " + trial + " — parallel did not find the true optimum\n  "
                                + gen.describe(procs));
            }
        }

        @Test
        @DisplayName("Parallel search also finds the true brute-force optimum on the heavy-comm diamond")
        void parallelSearchMatchesBruteForceOnHeavyCommDiamond() {
            TaskGraph g = graph(
                    new int[]{2, 3, 3, 2},
                    new int[][]{{0, 1, 10}, {0, 2, 10}, {1, 3, 10}, {2, 3, 10}});

            int expected = bruteForceOptimalMakespan(g, 2);
            int actual = solveInParallel(g, 2);

            assertEquals(expected, actual);
        }
    }

    // =====================================================================================
    // Lower bound soundness
    // =====================================================================================

    @Nested
    @DisplayName("Bottom-level lower bound soundness")
    class BoundSanityChecks {

        @Test
        @DisplayName("Bottom level of the graph's exit tasks equals their own weight")
        void bottomLevelOfExitTasksIsJustTheirOwnWeight() {
            TaskGraph g = graph(new int[]{3, 4, 5}, new int[][]{{0, 1, 0}, {1, 2, 0}});
            SearchContext ctx = new SearchContext(g, 2);
            assertEquals(5, ctx.getBottomLevel(2));
        }

        @Test
        @DisplayName("Bottom level equals the longest remaining path length, including comm-free assumption")
        void bottomLevelEqualsLongestDownstreamPath() {
            // 0 -> 1 -> 3: bottom-level of 0 along this path = 2+3+4 = 9
            // 0 -> 2 -> 3: bottom-level of 0 along this path = 2+1+4 = 7
            // max across both branches = 9
            TaskGraph g = graph(
                    new int[]{2, 3, 1, 4},
                    new int[][]{{0, 1, 0}, {0, 2, 0}, {1, 3, 0}, {2, 3, 0}});
            SearchContext ctx = new SearchContext(g, 2);
            assertEquals(9, ctx.getBottomLevel(0));
        }

        /**
         * Tests the mathematical property that pruning correctness depends on:
         *
         * <pre>
         *   lowerBound(partial schedule) &lt;= optimalCompletion(partial schedule)
         * </pre>
         *
         * For each trial graph, this test simulates a random walk through the search tree by
         * repeatedly picking a ready task and a random processor, placing the task, asserting the
         * bound at that depth, then continuing deeper — checking the invariant at <em>every</em>
         * level from depth 1 down to n−1 (one task left unscheduled so there is always a
         * non-trivial completion to compute). The walk is then fully unwound via {@code undo()} to
         * leave the search object in a clean state.
         *
         * <p>This is substantially stronger than only checking at depth 1: the bound is evaluated
         * at every node along a path through the search tree, covering the intermediate states
         * where most pruning decisions actually happen.
         *
         * <p>Note: This directly tests the bound's mathematical guarantee, unlike checking
         * {@code reportedMakespan >= trueOptimal} on the final result — any valid schedule already
         * satisfies that inequality regardless of whether the bound is sound.
         */
        @Test
        @DisplayName("Lower bound never exceeds the true optimal completion at any search-tree depth")
        void lowerBoundNeverExceedsOptimalCompletionAtAnyDepth() {
            Random rnd = new Random(99);

            for (int trial = 0; trial < 20; trial++) {
                int n = 4 + rnd.nextInt(3);          // 4..6 tasks
                int procs = 2 + rnd.nextInt(2);       // 2..3 processors
                GeneratedGraph gen = randomDag(rnd, n, 0.4, 6, 5);
                TaskGraph g = gen.graph();

                // Use SequentialAlgorithm's inherited place() / lowerBound() directly
                // so we test the exact bound the search uses for pruning decisions.
                SequentialAlgorithm search = new SequentialAlgorithm(g, procs);

                // Walk randomly from depth 1 down to n-1, checking the bound at every level.
                // We stop one task before completion so there is always a non-trivial
                // remaining subproblem for bruteForceOptimalCompletion to evaluate.
                int depth = 0;
                while (depth < n - 1) {
                    int task = search.utils.nextReadyTask();
                    if (task == -1) break;   // no ready task (disconnected graph corner case)

                    int proc = rnd.nextInt(procs);
                    search.place(task, proc);
                    depth++;

                    int bound = search.utils.lowerBound();
                    int[] partialProcOf   = search.localContext.processorOf.clone();
                    int[] partialStartTime = search.localContext.startTime.clone();
                    int[] partialFreeAt   = search.localContext.processorFreeAt.clone();

                    int optimalCompletion = bruteForceOptimalCompletion(
                            g, procs, partialProcOf, partialStartTime, partialFreeAt);

                    assertTrue(bound <= optimalCompletion,
                            "trial " + trial + ", depth=" + depth
                                    + ", task=" + task + ", proc=" + proc
                                    + " — lower bound " + bound
                                    + " exceeds the true optimal completion " + optimalCompletion
                                    + "; an unsound bound causes the search to prune the optimal branch\n  "
                                    + gen.describe(procs));
                }

                // Fully unwind the walk so the search object is cleanly reset.
                for (int i = 0; i < depth; i++) {
                    search.localContext.undo(search.getContext());
                }
            }
        }

        /**
         * Same bound-soundness check on the deterministic hand-crafted graphs, so failures
         * are reproducible and diagnosable without a random seed.
         */
        @Test
        @DisplayName("Lower bound is sound on the hand-crafted adversarial graphs")
        void lowerBoundIsSoundOnAdversarialGraphs() {
            // {weights}, {edges as {from, to, comm}}
            Object[][] cases = {
                    // Chain: only one ready task, but bound must still not overshoot.
                    {new int[]{2, 3, 4, 5}, new int[][]{{0, 1, 7}, {1, 2, 7}, {2, 3, 7}}, 2},
                    // Diamond with heavy comm costs.
                    {new int[]{2, 3, 3, 2}, new int[][]{{0, 1, 10}, {0, 2, 10}, {1, 3, 10}, {2, 3, 10}}, 2},
                    // Asymmetric outgoing comm costs.
                    {new int[]{2, 3, 3, 2}, new int[][]{{0, 1, 5}, {0, 2, 5}, {1, 3, 1}, {2, 3, 15}}, 2},
            };

            for (Object[] c : cases) {
                int[] weights = (int[]) c[0];
                int[][] edges = (int[][]) c[1];
                int procs = (int) c[2];
                TaskGraph g = graph(weights, edges);

                SequentialAlgorithm search = new SequentialAlgorithm(g, procs);

                int firstTask = -1;
                for (int t = 0; t < g.taskCount(); t++) {
                    if (g.parentCount(t) == 0) {
                        firstTask = t;
                        break;
                    }
                }

                for (int proc = 0; proc < procs; proc++) {
                    search.place(firstTask, proc);

                    int bound = search.utils.lowerBound();
                    int[] partialProcOf = search.localContext.processorOf.clone();
                    int[] partialStartTime = search.localContext.startTime.clone();
                    int[] partialFreeAt = search.localContext.processorFreeAt.clone();

                    int optimalCompletion = bruteForceOptimalCompletion(
                            g, procs, partialProcOf, partialStartTime, partialFreeAt);

                    assertTrue(bound <= optimalCompletion,
                            "graph weights=" + java.util.Arrays.toString(weights)
                                    + ", task=" + firstTask + ", proc=" + proc
                                    + " — lower bound " + bound
                                    + " exceeds the true optimal completion " + optimalCompletion);

                    search.localContext.undo(search.getContext());
                }
            }
        }
    }
}