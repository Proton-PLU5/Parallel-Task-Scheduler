package se306.scheduler.algorithm.parallel;

import se306.scheduler.algorithm.Algorithm;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

import java.util.*;
import java.util.concurrent.ForkJoinPool;

public class ParallelAlgorithm implements Algorithm {

    private final TaskGraph graph;
    private final int numProcessors;
    private final int[] bottomLevel;
    private final ForkJoinPool pool;
    private final int parallelDepthCutoff;
    private volatile int best;
    private volatile Schedule bestSchedule;

    public ParallelAlgorithm(TaskGraph graph, int numProcessors, int numThreads) {
        if (numProcessors < 1) {
            throw new IllegalArgumentException("numProcessors must be at least 1, was " + numProcessors);
        }

        this.graph = graph;
        this.numProcessors = numProcessors;
        this.bottomLevel = computeBottomLevel(graph);

        this.pool = new ForkJoinPool(numThreads);

        // A variable used to limit creation of new branches after this many tasks are placed.
        this.parallelDepthCutoff = Math.min(graph.taskCount(), 4);
    }

    /**
     * For every unscheduled task, estimate the longest chain of tasks that must be completed after it, including itself.
     * This is used to prune the search space.
     *
     * @param graph The task graph
     * @return An array where bottomLevel[i] is the length of the longest path from task i to the end task, including the weight of task i itself.
     */
    private int[] computeBottomLevel(TaskGraph graph) {
        int n = graph.taskCount();
        int[] bottomLevel = new int[n];
        int[] order = graph.topologicalOrder();

        // Initialize bottom level with the weight of each task
        for (int i = 0; i < n; i++) {
            bottomLevel[i] = graph.weight(i);
        }

        // Process tasks in reverse topological order
        for (int i = n - 1; i >= 0; i--) {
            int task = order[i];

            for (int k = graph.childStart(task); k < graph.childEnd(task); k++) {
                int child = graph.childAt(k);
                bottomLevel[task] = Math.max(bottomLevel[task], graph.weight(task) + bottomLevel[child]);
            }
        }

        return bottomLevel;
    }

    /**
     * Solve for the best possible schedule
     *
     * @return The best schedule
     */
    public Schedule solve() {
        int n = graph.taskCount();
        bestSchedule = null;
        best = Integer.MAX_VALUE;

        int[] processorOf = new int[n]; // processor each task is assigned to
        int[] startTime = new int[n]; // start time of each task
        int[] processorFreeAt = new int[numProcessors];
        int[] indegreeRemaining = new int[n];

        // Populate initial values
        Arrays.fill(processorOf, -1);
        Arrays.fill(startTime, -1);

        for (int t = 0; t < n; t++) {
            indegreeRemaining[t] = graph.parentCount(t);
        }

        int makespan = 0;

        SearchTask root = new SearchTask(
                this,
                makespan,
                startTime,
                processorOf,
                processorFreeAt,
                indegreeRemaining,
                0,
                0
        );

        pool.invoke(root);
        pool.shutdown();

        return this.bestSchedule;
    }

    /**
     * Compare the makeSpan with the current best, and if it is smaller than the current best,
     * Update the best schedule to use the new startTimes and processorOfs.
     *
     * @param makespan The makespan of the new schedule
     * @param startTime The startTime of the new schedule
     * @param processorOf the processorOf of the new schedule
     */
    public synchronized void compareAndSetBestSchedule(
            int makespan,
            int[] startTime,
            int[] processorOf) {
        if (makespan < this.best) {
            best = makespan;
            bestSchedule = new Schedule(graph, startTime.clone(), processorOf.clone(), numProcessors);
        }
    }

    public TaskGraph getGraph() {
        return this.graph;
    }

    public int getBest() {
        return this.best;
    }

    public int getNumProcessors() {
        return this.numProcessors;
    }

    public int getBottomLevel(int task) {
        return bottomLevel[task];
    }

    public int getParallelDepthCutoff() {
        return parallelDepthCutoff;
    }
}
