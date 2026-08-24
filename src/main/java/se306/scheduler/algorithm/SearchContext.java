package se306.scheduler.algorithm;

import se306.scheduler.algorithm.metrics.SearchMetrics;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.gui.SearchListener;
import se306.scheduler.schedule.Schedule;

/**
 * The shared current best state. This class contains the shared state between threads, and handles the concurrency
 * management when updating the current best state.
 */
public class SearchContext {

    private final SearchMetrics metrics;
    private final TaskGraph graph;

    private final int numProcessors;
    private final int[] bottomLevel;
    private final int totalWork;
    private final SearchListener listener;

    private volatile int best = Integer.MAX_VALUE;
    private volatile Schedule bestSchedule;

    public SearchContext(TaskGraph graph, int numProcessors) {
        this(graph, numProcessors, null);
    }

    public SearchContext(TaskGraph graph, int numProcessors, SearchListener listener) {
        if (numProcessors < 1) {
            throw new IllegalArgumentException("numProcessors must be at least 1, was " + numProcessors);
        }
        this.graph = graph;
        this.numProcessors = numProcessors;
        this.bottomLevel = computeBottomLevel(graph);
        this.totalWork = computeTotalWork(graph);
        this.listener = listener;
        this.metrics = new SearchMetrics();
    }

    /**
     * Sums the weight of every task in the graph, used by {@link #getLoadBound()}.
     *
     * @param graph The task graph
     * @return The total weight of all tasks
     */
    private int computeTotalWork(TaskGraph graph) {
        int n = graph.taskCount();
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += graph.weight(i);
        }
        return sum;
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
     * A static lower bound on the makespan: every task's weight must be assigned to exactly one
     * processor, so the total work across all processors is fixed at totalWork regardless of how
     * the schedule turns out. No processor can do more than M work by time M, so summed over all
     * processors that's numProcessors * M >= totalWork, giving M >= ceil(totalWork / numProcessors).
     * This holds for every partial schedule (it doesn't depend on search state at all), so it's
     * cheap to fold into every lowerBound() call alongside the critical-path bound.
     *
     * @return the load-balance lower bound on the makespan
     */
    public int getLoadBound() {
        return (totalWork + numProcessors - 1) / numProcessors;
    }

    public void runGreedyAlgorithm() {
        TaskGraph graph = getGraph();
        Algorithm greedyAlogorithm = new ListScheduler(graph, getNumProcessors());

        Schedule greedySchedule = greedyAlogorithm.solve();
        compareAndSetBestSchedule(greedySchedule);
    }

    /**
     * Compare the makeSpan with the current best, and if it is smaller than the current best,
     * Update the best schedule to use the new startTimes and processorOfs.
     *
     * @param makespan The makespan of the new schedule
     * @param startTime The startTime of the new schedule
     * @param processorOf the processorOf of the new schedule
     */
    public void compareAndSetBestSchedule(
            int makespan,
            int[] startTime,
            int[] processorOf) {

        // Fast, lock-free bail-out: `best` is volatile, so this lets the overwhelming majority
        // of calls - schedules that don't improve on the best found so far - skip both the
        // allocation below and the synchronized block entirely.
        if (makespan >= this.best) {
            return;
        }

        // Clone/construct outside the lock. These arrays belong to the calling thread alone -
        // each search branch owns its own startTime/processorOf - so nothing else can mutate
        // them concurrently, and there's no reason to hold the lock while paying for this
        // allocation.
        Schedule improved = new Schedule(graph, startTime.clone(), processorOf.clone(), numProcessors);

        synchronized (this) {
            if (makespan >= this.best) {
                return;
            }
            best = makespan;
            bestSchedule = improved;
        }

        // Notified outside the lock: no worker should be able to block behind a listener callback.
        if (listener != null) {
            listener.onNewBestSchedule(graph, improved);
        }
    }

    public void compareAndSetBestSchedule(Schedule improved) {
        synchronized (this) {
            if (improved.makespan() >= this.best) {
                return;
            }
            best = improved.makespan();
            bestSchedule = improved;
        }

        if (listener != null) {
            listener.onNewBestSchedule(graph, improved);
        }
    }

    public TaskGraph getGraph() { return graph; }
    public int getNumProcessors() { return numProcessors; }
    public int getBottomLevel(int task) { return bottomLevel[task]; }
    public int getBest() { return best; }
    public Schedule getBestSchedule() { return bestSchedule; }
    public SearchMetrics getMetrics() { return metrics; }
}
