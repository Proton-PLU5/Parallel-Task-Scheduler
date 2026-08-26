package se306.scheduler.algorithm;

import se306.scheduler.algorithm.core.AbstractSearch;
import se306.scheduler.algorithm.core.SearchContext;
import se306.scheduler.algorithm.parallel.ParallelAlgorithm;
import se306.scheduler.algorithm.parallel.ParallelSearch;
import se306.scheduler.algorithm.sequential.SequentialAlgorithm;
import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

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
 * Cross-checks the branch-and-bound search ({@link AbstractSearch} / {@link SequentialAlgorithm} /
 * {@link ParallelSearch}) against an independent brute-force solver to make sure it actually finds
 * the optimal schedule, not just a valid one.
 *
 * <p>{@link AlgorithmUtils#nextReadyTask()} always picks the lowest-index ready task, so the search
 * only branches on processor choice for a fixed task order. The brute force here enumerates all
 * topological orders too, so it isn't relying on the same assumption.
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
        // i -> j only for i < j, guarantees acyclic.
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
    // Brute-force reference solver
    // =====================================================================================

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

    private static int[] decodeAssignment(int mask, int numProcessors, int n) {
        int[] procOf = new int[n];
        for (int i = 0; i < n; i++) {
            procOf[i] = mask % numProcessors;
            mask /= numProcessors;
        }
        return procOf;
    }

    /**
     * Exhaustively tries every topological order combined with every processor assignment.
     * Deliberately shares no code with {@link AbstractSearch} so a bug there can't be mirrored here.
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
     * Same idea as {@link #bruteForceOptimalMakespan}, but some tasks are already fixed
     * (fixedProcOf == -1 means not yet scheduled). Used to validate lower-bound soundness against
     * the true best completion of a partial schedule.
     */
    private static int bruteForceOptimalCompletion(
            TaskGraph graph,
            int numProcessors,
            int[] fixedProcOf,
            int[] fixedStartTime,
            int[] processorFreeAt) {

        int n = graph.taskCount();

        List<Integer> remaining = new ArrayList<>();
        for (int t = 0; t < n; t++) {
            if (fixedProcOf[t] == -1) remaining.add(t);
        }
        int r = remaining.size();

        // indegree within the remaining subgraph only
        int[] indegree = new int[n];
        for (int t : remaining) {
            for (int k = graph.parentStart(t); k < graph.parentEnd(t); k++) {
                int parent = graph.parentAt(k);
                if (fixedProcOf[parent] == -1) {
                    indegree[t]++;
                }
            }
        }

        List<int[]> remainingOrders = new ArrayList<>();
        boolean[] used = new boolean[n];
        int[] cur = new int[r];
        remainingOrderBacktrack(graph, remaining, indegree, used, cur, 0, remainingOrders);

        int numAssignments = (int) Math.pow(numProcessors, r);
        int best = Integer.MAX_VALUE;

        for (int[] order : remainingOrders) {
            for (int mask = 0; mask < numAssignments; mask++) {
                int[] procOf = fixedProcOf.clone();
                int[] startTime = fixedStartTime.clone();
                int[] freeAt = processorFreeAt.clone();

                int tmp = mask;
                for (int i = 0; i < r; i++) {
                    procOf[order[i]] = tmp % numProcessors;
                    tmp /= numProcessors;
                }

                int makespan = 0;
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
    // Schedule validity checks
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
        void chainGraphHasNoParallelismOpportunity() {
            // 0->1->2->3, weights 2,3,4,5. Comm cost doesn't matter, whole chain runs on one proc.
            TaskGraph g = graph(
                    new int[]{2, 3, 4, 5},
                    new int[][]{{0, 1, 7}, {1, 2, 7}, {2, 3, 7}});

            for (int procs : new int[]{1, 2, 3, 4}) {
                Schedule schedule = new SequentialAlgorithm(g, procs).solve();
                assertEquals(14, schedule.makespan());
                assertEquals(bruteForceOptimalMakespan(g, procs), schedule.makespan());
                assertValidSchedule(g, schedule);
            }
        }

        @Test
        void independentTasksBalanceAcrossProcessors() {
            TaskGraph g = graph(new int[]{4, 4, 4, 4}, new int[][]{});

            Schedule schedule = new SequentialAlgorithm(g, 2).solve();
            assertEquals(8, schedule.makespan());
            assertEquals(bruteForceOptimalMakespan(g, 2), schedule.makespan());
            assertValidSchedule(g, schedule);
        }

        @Test
        void moreProcessorsThanTasks() {
            TaskGraph g = graph(new int[]{5, 5, 5}, new int[][]{});

            Schedule schedule = new SequentialAlgorithm(g, 5).solve();
            assertEquals(5, schedule.makespan());
            assertEquals(bruteForceOptimalMakespan(g, 5), schedule.makespan());
            assertValidSchedule(g, schedule);
        }

        @Test
        void singleTask() {
            TaskGraph g = graph(new int[]{7}, new int[][]{});
            for (int procs : new int[]{1, 2, 3}) {
                Schedule schedule = new SequentialAlgorithm(g, procs).solve();
                assertEquals(7, schedule.makespan());
                assertValidSchedule(g, schedule);
            }
        }

        @Test
        @DisplayName("heavy comm cost forces everything onto one processor")
        void diamondWithHeavyCommCostForcesColocation() {
            TaskGraph g = graph(
                    new int[]{2, 3, 3, 2},
                    new int[][]{{0, 1, 10}, {0, 2, 10}, {1, 3, 10}, {2, 3, 10}});

            Schedule schedule = new SequentialAlgorithm(g, 2).solve();
            assertEquals(10, schedule.makespan());
            assertEquals(bruteForceOptimalMakespan(g, 2), schedule.makespan());
            assertValidSchedule(g, schedule);
        }

        @Test
        void diamondWithZeroCommCostAllowsParallelism() {
            TaskGraph g = graph(
                    new int[]{2, 3, 3, 2},
                    new int[][]{{0, 1, 0}, {0, 2, 0}, {1, 3, 0}, {2, 3, 0}});

            Schedule schedule = new SequentialAlgorithm(g, 2).solve();
            assertEquals(7, schedule.makespan()); // critical path 0->1->3 = 2+3+2
            assertEquals(bruteForceOptimalMakespan(g, 2), schedule.makespan());
            assertValidSchedule(g, schedule);
        }

        @Test
        @DisplayName("spec Figure 1 example")
        void specFigure1ExampleMatchesBruteForceOptimum() {
            TaskGraph g = graph(
                    new int[]{2, 3, 3, 2},
                    new int[][]{{0, 1, 1}, {0, 2, 2}, {1, 3, 2}, {2, 3, 1}});

            for (int procs : new int[]{1, 2, 3}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule schedule = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, schedule.makespan(), "procs=" + procs);
                assertValidSchedule(g, schedule);
            }
        }

        @Test
        void diamondSmallCommCostMatchesBruteForceOptimum() {
            TaskGraph g = graph(
                    new int[]{4, 5, 5, 3},
                    new int[][]{{0, 1, 1}, {0, 2, 1}, {1, 3, 1}, {2, 3, 1}});

            for (int procs : new int[]{2, 4}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule schedule = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, schedule.makespan(), "procs=" + procs);
                assertValidSchedule(g, schedule);
            }
        }

        @Test
        void diamondLargeCommCostMatchesBruteForceOptimum() {
            TaskGraph g = graph(
                    new int[]{2, 3, 6, 9},
                    new int[][]{{0, 1, 3}, {0, 2, 3}, {1, 3, 2}, {2, 3, 8}});

            for (int procs : new int[]{2, 4}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule schedule = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, schedule.makespan(), "procs=" + procs);
                assertValidSchedule(g, schedule);
            }
        }

        @Test
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
         * A(1) and B(20) both feed into C. nextReadyTask() always picks A first, so this checks
         * the search still finds the optimum even though the heavy sibling comes second.
         */
        @Test
        void asymmetricReadyTasksWithSharedJoin() {
            TaskGraph g = graph(
                    new int[]{1, 20, 1},
                    new int[][]{{0, 2, 0}, {1, 2, 0}});

            for (int procs : new int[]{1, 2, 3}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule actual = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, actual.makespan(), "procs=" + procs);
                assertValidSchedule(g, actual);
            }
        }

        /** Fork-join where B->D comm cost (1) and C->D comm cost (15) are wildly different. */
        @Test
        void asymmetricOutgoingCommCostsOnJoin() {
            TaskGraph g = graph(
                    new int[]{2, 3, 3, 2},
                    new int[][]{{0, 1, 5}, {0, 2, 5}, {1, 3, 1}, {2, 3, 15}});

            for (int procs : new int[]{2, 3}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule actual = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, actual.makespan(), "procs=" + procs);
                assertValidSchedule(g, actual);
            }
        }

        /** Same idea but the asymmetry is on the fork side (A->B=2, A->C=10). */
        @Test
        void asymmetricIncomingCommCostsOnFork() {
            TaskGraph g = graph(
                    new int[]{2, 3, 3, 2},
                    new int[][]{{0, 1, 2}, {0, 2, 10}, {1, 3, 5}, {2, 3, 5}});

            for (int procs : new int[]{2, 3}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule actual = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, actual.makespan(), "procs=" + procs);
                assertValidSchedule(g, actual);
            }
        }

        @Test
        void twoIndependentBranchesWithSharedSink() {
            TaskGraph g = graph(
                    new int[]{3, 4, 5, 2, 3},
                    new int[][]{{0, 1, 0}, {2, 3, 0}, {1, 4, 2}, {3, 4, 2}});

            for (int procs : new int[]{1, 2, 3}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule actual = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, actual.makespan(), "procs=" + procs);
                assertValidSchedule(g, actual);
            }
        }

        @Test
        void fullyIndependentTasksVariousProcessorCounts() {
            TaskGraph g = graph(new int[]{5, 4, 5}, new int[][]{});

            for (int procs : new int[]{1, 2, 3, 4, 5}) {
                int expected = bruteForceOptimalMakespan(g, procs);
                Schedule actual = new SequentialAlgorithm(g, procs).solve();
                assertEquals(expected, actual.makespan(), "procs=" + procs);
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

        @Test
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
                        "trial " + trial + "\n  " + gen.describe(procs));
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

        @Test
        void parallelAndSequentialBothMatchBruteForceOnRandomGraphs() {
            Random rnd = new Random(7);
            int trials = 50;

            for (int trial = 0; trial < trials; trial++) {
                int n = 4 + rnd.nextInt(3);
                int procs = 1 + rnd.nextInt(4);
                double edgeProbability = 0.3 + rnd.nextDouble() * 0.4;
                GeneratedGraph gen = randomDag(rnd, n, edgeProbability, 6, 5);
                TaskGraph g = gen.graph();

                int expected = bruteForceOptimalMakespan(g, procs);
                int sequentialResult = new SequentialAlgorithm(g, procs).solve().makespan();
                int parallelResult = solveInParallel(g, procs);

                assertEquals(expected, sequentialResult,
                        "trial " + trial + " (sequential)\n  " + gen.describe(procs));
                assertEquals(expected, parallelResult,
                        "trial " + trial + " (parallel)\n  " + gen.describe(procs));
            }
        }

        @Test
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
        void bottomLevelOfExitTasksIsJustTheirOwnWeight() {
            TaskGraph g = graph(new int[]{3, 4, 5}, new int[][]{{0, 1, 0}, {1, 2, 0}});
            SearchContext ctx = new SearchContext(g, 2);
            assertEquals(5, ctx.getBottomLevel(2));
        }

        @Test
        void bottomLevelEqualsLongestDownstreamPath() {
            // 0->1->3 = 2+3+4 = 9, 0->2->3 = 2+1+4 = 7, max = 9
            TaskGraph g = graph(
                    new int[]{2, 3, 1, 4},
                    new int[][]{{0, 1, 0}, {0, 2, 0}, {1, 3, 0}, {2, 3, 0}});
            SearchContext ctx = new SearchContext(g, 2);
            assertEquals(9, ctx.getBottomLevel(0));
        }

        /**
         * Pruning is only safe if lowerBound(partial) <= true optimal completion of that partial
         * schedule. This walks randomly through the search tree, checking that invariant at every
         * depth (not just depth 1, since that's where most pruning actually happens), then unwinds
         * via undo() to leave the search object clean.
         */
        @Test
        void lowerBoundNeverExceedsOptimalCompletionAtAnyDepth() {
            Random rnd = new Random(99);

            for (int trial = 0; trial < 20; trial++) {
                int n = 4 + rnd.nextInt(3);
                int procs = 2 + rnd.nextInt(2);
                GeneratedGraph gen = randomDag(rnd, n, 0.4, 6, 5);
                TaskGraph g = gen.graph();

                SequentialAlgorithm search = new SequentialAlgorithm(g, procs);

                // stop one task short of completion so there's always a nontrivial subproblem
                int depth = 0;
                while (depth < n - 1) {
                    int task = search.utils.nextReadyTask();
                    if (task == -1) break;   // no ready task (disconnected graph corner case)

                    int proc = rnd.nextInt(procs);
                    search.place(task, proc);
                    depth++;

                    int bound = search.utils.lowerBound();
                    int[] partialProcOf   = search.localContext.getProcessorOf().clone();
                    int[] partialStartTime = search.localContext.getStartTime().clone();
                    int[] partialFreeAt   = search.localContext.getProcessorFreeAt().clone();

                    int optimalCompletion = bruteForceOptimalCompletion(
                            g, procs, partialProcOf, partialStartTime, partialFreeAt);

                    assertTrue(bound <= optimalCompletion,
                            "trial " + trial + ", depth=" + depth + ", task=" + task
                                    + ", proc=" + proc + " — bound " + bound
                                    + " > true completion " + optimalCompletion
                                    + "\n  " + gen.describe(procs));
                }

                for (int i = 0; i < depth; i++) {
                    search.localContext.undo(search.getContext());
                }
            }
        }

        @Test
        void lowerBoundIsSoundOnAdversarialGraphs() {
            Object[][] cases = {
                    {new int[]{2, 3, 4, 5}, new int[][]{{0, 1, 7}, {1, 2, 7}, {2, 3, 7}}, 2},
                    {new int[]{2, 3, 3, 2}, new int[][]{{0, 1, 10}, {0, 2, 10}, {1, 3, 10}, {2, 3, 10}}, 2},
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
                    int[] partialProcOf = search.localContext.getProcessorOf().clone();
                    int[] partialStartTime = search.localContext.getStartTime().clone();
                    int[] partialFreeAt = search.localContext.getProcessorFreeAt().clone();

                    int optimalCompletion = bruteForceOptimalCompletion(
                            g, procs, partialProcOf, partialStartTime, partialFreeAt);

                    assertTrue(bound <= optimalCompletion,
                            "weights=" + java.util.Arrays.toString(weights)
                                    + ", task=" + firstTask + ", proc=" + proc
                                    + " — bound " + bound + " > true completion " + optimalCompletion);

                    search.localContext.undo(search.getContext());
                }
            }
        }
    }
}