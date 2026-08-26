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

    // The bottom level represents longest remaining work from that task to an exit task (including the task itself)
    private final int[] bottomLevel;

    // The fixed order of task IDs used to decide which ready tasks to try first.
    // Tasks sorted by descending bottomLevel (more critical/longer downstream chain first)
    private final int[] taskPriorityOrder;

    // totalWork is the sum of all task weights in the graph.
    private final int totalWork;

    // The search listener used for updating the Gantt Chart
    private final SearchListener listener;

    // The current best makespan.
    private volatile int best = Integer.MAX_VALUE;

    // The current best schedule.
    private volatile Schedule bestSchedule;

    // Constructor to create a search context without UI listener
    public SearchContext(TaskGraph graph, int numProcessors) {
        this(graph, numProcessors, null);
    }

    // Constructor to create the search context.
    public SearchContext(TaskGraph graph, int numProcessors, SearchListener listener) {
        if (numProcessors < 1) {
            throw new IllegalArgumentException("numProcessors must be at least 1, was " + numProcessors);
        }
        this.graph = graph;
        this.numProcessors = numProcessors;

        // Compute the bottom levels and task priority order
        this.bottomLevel = computeBottomLevel(graph);
        this.taskPriorityOrder = computeTaskPriorityOrder(graph);

        // Compute the total work of the graph for load balancing lower bound calculations
        this.totalWork = computeTotalWork(graph);

        this.listener = listener;
        this.metrics = new SearchMetrics();
    }

    /**
     * Sums the weight of every task in the graph, used by getLoadBound()
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
     * @return An array where bottomLevel[i] is the length of the longest path from task i to the end task,
     *         including the weight of task i itself.
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
     * All tasks sorted by descending bottom level (ties broken by topological position, so a
     * parent always sorts before an equal-bottom-level child). Iterating ready tasks in this
     * order sends the DFS down critical-path branches first, which tends to find near-optimal
     * schedules early and makes every subsequent bound check stronger. The search still visits
     * every ready task at every node, so this changes only the visit order, never completeness.
     *
     * @return An array of task IDs sorted by descending bottom level, with topological tie-breaking.
     */
    private int[] computeTaskPriorityOrder(TaskGraph graph) {
        int n = graph.taskCount();

        // Get the topological order of tasks to use as a tie-breaker for equal bottom levels.
        int[] topological = graph.topologicalOrder();
        int[] order = topological.clone();

        // Insertion sort by descending bottom level.
        // Stable, so the topological tie-break holds.
        for (int i = 1; i < n; i++) {
            int task = order[i];

            int j = i - 1;

            // Shift lower-priority tasks (smaller bottom level) right to insert the "task"
            // so order stays sorted by descending bottom level; equal levels keep original order
            while (j >= 0 && bottomLevel[order[j]] < bottomLevel[task]) {
                order[j + 1] = order[j];
                j--;
            }

            order[j + 1] = task;
        }
        return order;
    }

    /**
     * A lower bound on how long the schedule must take, based on total work and any idle
     * time already locked in.
     *
     * If you split all the work evenly across every processor, the busiest one still needs
     * at least totalWork / numProcessors time. So the schedule can never finish faster than
     * that, no matter how the remaining tasks get placed.
     *
     * Idle time makes this worse. Once a processor sits idle, that time is gone for good,
     * later tasks only get added after it, never into the gap. So that wasted time still has
     * to fit into the schedule somewhere, the same way real work does. Adding idleTime to
     * totalWork before dividing accounts for that.
     *
     * @param idleTime total idle time committed so far across all processors
     * @return the idle-aware load-balance lower bound on the makespan
     */
    public int getLoadBound(int idleTime) {
        return (totalWork + idleTime + numProcessors - 1) / numProcessors;
    }

    /**
     * Run a greedy algorithm to get a good first best makespan before performing the
     * heavier DFS BnB.
     */
    public void runGreedyAlgorithm() {
        TaskGraph graph = getGraph();

        // Run two greedy passes to seed the best makespan.
        // One using the plain topological order, and using the critical-path priority via taskPriorityOrder
        // Whichever is better wins, a tighter seed makes every
        // bound check in the exact search stronger from the very first node.

        compareAndSetBestSchedule(new ListScheduler(graph, getNumProcessors()).solve());
        compareAndSetBestSchedule(new ListScheduler(graph, getNumProcessors(), taskPriorityOrder).solve());
    }

    /**
     * Compare the makeSpan with the current best, and if it is smaller than the current best,
     * Update the best schedule to use the new startTimes and processorOfs.
     *
     * @param makespan The makespan of the new schedule
     * @param startTime The start times of the tasks
     * @param processorOf The processor assignments of the tasks
     */
    public void compareAndSetBestSchedule(int makespan, int[] startTime, int[] processorOf) {
        // If the current makespan is worse than the best then don't even try updating.
        if (makespan >= this.best) {
            return;
        }

        // Otherwise construct a new schedule using the new startTimes, processorOfs and makespan.
        Schedule improved = new Schedule(graph, startTime.clone(), processorOf.clone(), numProcessors);

        compareAndSetBestSchedule(improved);
    }

    /**
     * An alternative way to compare and set best schedule using the LocalContext object
     * @param context The local context of a search task.
     */
    public void compareAndSetBestSchedule(LocalContext context) {
        int makespan = context.makespan;
        int[] startTime = context.startTime;
        int[] processorOf = context.processorOf;

        compareAndSetBestSchedule(makespan, startTime, processorOf);
    }

    /**
     * Another alternative way to compare and set best schedule using the Schedule object
     * @param potentialSchedule The schedule to compare against.
     */
    public void compareAndSetBestSchedule(Schedule potentialSchedule) {
        int makespan = potentialSchedule.makespan();

        // Update the make span and best schedule using a monitor to ensure concurrency control.
        synchronized (this) {
            // Run again to check if any other threads may have updated in the time we were
            // checking.
            if (makespan >= this.best) {
                return;
            }

            // Perform update.
            best = makespan;
            bestSchedule = potentialSchedule;
        }

        // Notify the listener that a new best schedule is available.
        if (listener != null) {
            listener.onNewBestSchedule(graph, potentialSchedule);
        }
    }

    public TaskGraph getGraph() { return graph; }
    public int getNumProcessors() { return numProcessors; }
    public int getBottomLevel(int task) { return bottomLevel[task]; }

    /** All tasks by descending bottom level. Read-only */
    public int[] getTaskPriorityOrder() { return taskPriorityOrder; }

    public int getBest() { return best; }
    public Schedule getBestSchedule() { return bestSchedule; }
    public SearchMetrics getMetrics() { return metrics; }
}
