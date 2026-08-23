package se306.scheduler.algorithm;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.gui.SearchListener;
import se306.scheduler.schedule.Schedule;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The shared current best state. This class contains the shared state between threads, and handles the concurrency
 * management when updating the current best state.
 */
public class SearchContext {
    private final TaskGraph graph;
    private final int numProcessors;
    private final int[] bottomLevel;
    private final SearchListener listener;

    private volatile int best = Integer.MAX_VALUE;
    private volatile Schedule bestSchedule;

    private final AtomicLong branchesExplored = new AtomicLong();
    private final AtomicLong branchesPruned = new AtomicLong();

    public SearchContext(TaskGraph graph, int numProcessors) {
        if (numProcessors < 1) {
            throw new IllegalArgumentException("numProcessors must be at least 1, was " + numProcessors);
        }
        this.graph = graph;
        this.numProcessors = numProcessors;
        this.bottomLevel = computeBottomLevel(graph);
        this.listener = null;
    }

    public SearchContext(TaskGraph graph, int numProcessors, SearchListener listener) {
        if (numProcessors < 1) {
            throw new IllegalArgumentException("numProcessors must be at least 1, was " + numProcessors);
        }
        this.graph = graph;
        this.numProcessors = numProcessors;
        this.bottomLevel = computeBottomLevel(graph);
        this.listener = listener;
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
            
            // When we have a new best schedule, call the listener to update the GUI
            if (listener != null) {
                listener.onNewBestSchedule(graph, bestSchedule);
            }
        }
    }

    public TaskGraph getGraph() { return graph; }
    public int getNumProcessors() { return numProcessors; }
    public int getBottomLevel(int task) { return bottomLevel[task]; }
    public int getBest() { return best; }
    public Schedule getBestSchedule() { return bestSchedule; }

    public void incrementBranchesExplored() { branchesExplored.incrementAndGet(); }
    public void incrementBranchesPruned() { branchesPruned.incrementAndGet(); }
    public long getBranchesExplored() { return branchesExplored.get(); }
    public long getBranchesPruned() { return branchesPruned.get(); }
}
