package se306.scheduler.schedule.parallel;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ParallelAlgorithmn {

    private final TaskGraph graph;
    private final int numProcessors;
    private int scheduledCount;

    private int best;
    private Schedule bestSchedule;
    private int currentBound;
    private final int[] bottomLevel;

    private Executor executor;

    public ParallelAlgorithmn(TaskGraph graph, int numProcessors, int numThreads) {
        if (numProcessors < 1) {
            throw new IllegalArgumentException("numProcessors must be at least 1, was " + numProcessors);
        }

        this.graph = graph;
        this.numProcessors = numProcessors;
        this.executor = Executors.newFixedThreadPool(numThreads);
        this.bottomLevel = computeBottomLevel(graph);
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


    public Schedule solve() {
        int n = graph.taskCount();
        int makespan = 0;
        bestSchedule = null;
        best = Integer.MAX_VALUE;

        int[] processorOf = new int[n]; // processor each task is assigned to
        int[] startTime = new int[n]; // start time of each task
        int[] processorFreeAt = new int[numProcessors];
        int[] indegreeRemaining = new int[n];

        // Populate initial values
        java.util.Arrays.fill(processorOf, -1);
        java.util.Arrays.fill(startTime, -1);

        for (int t = 0; t < n; t++) {
            indegreeRemaining[t] = graph.parentCount(t);
        }

        int startTask = 0;

        SearchTask task = new SearchTask(
                this,
                makespan,
                startTime,
                indegreeRemaining,
                best,
                startTask,
                0
        );

        return this.bestSchedule;
    }

    public synchronized void setBestSchedule(Schedule bestSchedule) {
        this.bestSchedule = bestSchedule;
    }

    public synchronized void setBest(int newBest) {
        this.best = newBest;
    }

    public synchronized void compareAndSetBestSchedule(
            int makespan,
            int[] startTime,
            int[] processorOf) {
        if (makespan < this.getBest()) {
            setBest(makespan);
            bestSchedule = new Schedule(graph, startTime.clone(), processorOf.clone(), numProcessors);
        }
    }

    public synchronized void updateBound(int newBound) {
        this.currentBound = Math.max(currentBound, newBound);
    }

    public void createNewThread(SearchTask task) {
        this.executor.execute(task);
    }

    public int getNumProcessors() {
        return this.numProcessors;
    }

    public int getLowerBound() {
        return currentBound;
    }

    public int getBest() {
        return this.best;
    }

    public TaskGraph getGraph() {
        return this.graph;
    }

    public int getBottomLevel(int task) {
        return this.bottomLevel[task];
    }


}
