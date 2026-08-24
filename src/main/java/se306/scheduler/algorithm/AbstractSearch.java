package se306.scheduler.algorithm;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.RecursiveAction;

/**
 *
 * NOTE: Even for the sequential we still extend the recursive action, as the parallel implementation requires it
 *       doesn't cost much and still works as expected in the sequential scenario. This way we can avoid having to
 *       create a wrapper just for the recursive action. The sequential search never calls the fork() / join() methods
 *       itself so that part is just unused in that scenario.
 */
public abstract class AbstractSearch extends RecursiveAction {

    protected record LogEntry(int task, int processor, int previousFreeAt, int previousMakespan, int previousBound) {}

    /**
     * How many branches a search accumulates locally before pushing them to the shared counters.
     * Every node in the tree counts, so touching a shared LongAdder per node was by far the most
     * expensive thing the metrics did; batching drops that traffic by three orders of magnitude
     * while leaving the once-a-second GUI reading accurate to within one batch per live thread.
     */
    private static final int COUNTER_FLUSH_INTERVAL = 1024;

    // Shared state context
    protected final SearchContext ctx;

    public int[] processorOf;
    public int[] startTime;
    public int[] processorFreeAt;
    protected int[] indegreeRemaining;
    protected int[] taskCountOn;

    protected int scheduledCount;
    protected int makespan;

    // Critical Path Bound
    protected int currentBound;

    protected Deque<LogEntry> log;

    // Thread-confined branch counters, flushed to the shared metrics in batches. Deliberately not
    // copied by the child constructor: a forked child starts its own batch from zero, and the
    // parent keeps ownership of everything it has counted so far.
    private long localExplored;
    private long localPruned;

    /**
     * The primary constructor
     * @param ctx the shared state context object
     */
    protected AbstractSearch(SearchContext ctx) {
        this.ctx = ctx;
        int n = ctx.getGraph().taskCount();

        this.processorOf = new int[n];
        this.startTime = new int[n];
        this.processorFreeAt = new int[ctx.getNumProcessors()];
        this.indegreeRemaining = new int[n];
        this.taskCountOn = new int[ctx.getNumProcessors()];

        // Populate initial values
        Arrays.fill(processorOf, -1);
        Arrays.fill(startTime, -1);
        for (int t = 0; t < n; t++) {
            indegreeRemaining[t] = ctx.getGraph().parentCount(t);
        }

        this.log = new ArrayDeque<>();
    }

    /**
     * The secondary constructor, used for creating child search tasks that share the same context
     *
     * @param parent the parent AbstractSearch object
     */
    protected AbstractSearch(AbstractSearch parent) {
        this.ctx = parent.ctx;
        this.processorOf = parent.processorOf.clone();
        this.startTime = parent.startTime.clone();
        this.processorFreeAt = parent.processorFreeAt.clone();
        this.indegreeRemaining = parent.indegreeRemaining.clone();
        this.taskCountOn = parent.taskCountOn.clone();

        this.scheduledCount = parent.scheduledCount;
        this.makespan = parent.makespan;
        this.currentBound = parent.currentBound;

        // Create a fresh log, this clone is a new branch, so it never needs to undo
        // state it inherited from its parent.
        this.log = new ArrayDeque<>();
    }

    public int lowerBound() {
        return Math.max(makespan, Math.max(currentBound, ctx.getLoadBound()));
    }

    /**
     * Determines the next ready task whose dependencies have already been scheduled.
     *
     * TODO: Optimize this step so its better than O(N)?
     *
     * @return an integer representing the task.
     */
    public int nextReadyTask() {
        for (int task = 0; task < ctx.getGraph().taskCount(); task++) {
            if (processorOf[task] == -1 && indegreeRemaining[task] == 0) return task;
        }
        return -1;
    }

    /**
     * Places a task into the partial schedule by populating it with the new timestamps after the
     * task has been added, as well as creates a log entry to undo the scheduling
     * when we are backtracking.
     *
     * @param task The task to be placed
     * @param processor The processor the task should be scheduled onto.
     */
    public void place(int task, int processor) {
        int ready = processorFreeAt[processor];
        TaskGraph graph = ctx.getGraph();

        // Task can't start until all predecessors have finished (plus comm cost
        // if the predecessor ran on a different processor)
        for (int k = graph.parentStart(task); k < graph.parentEnd(task); k++) {
            int pred = graph.parentAt(k);
            int predFinish = startTime[pred] + graph.weight(pred);
            int comm = (processorOf[pred] == processor) ? 0 : graph.commCost(pred, task);
            ready = Math.max(ready, predFinish + comm);
        }

        // Log the previous state for backtracking
        log.push(new LogEntry(task, processor, processorFreeAt[processor], makespan, currentBound));

        // Update the state with this task scheduled
        processorOf[task] = processor;
        startTime[task] = ready;
        processorFreeAt[processor] = ready + graph.weight(task);
        taskCountOn[processor]++;
        makespan = Math.max(makespan, processorFreeAt[processor]);

        // Update the current bound
        currentBound = Math.max(currentBound, ready + ctx.getBottomLevel(task));

        scheduledCount++;

        // Decrease indegree of children
        for (int k = graph.childStart(task); k < graph.childEnd(task); k++) {
            int child = graph.childAt(k);
            indegreeRemaining[child]--;
        }
    }

    /**
     * Undoes the last scheduling operation by popping the log entry and restoring the previous state.
     * This is used for backtracking in the DFS search.
     */
    public void undo() {
        LogEntry entry = log.pop();
        startTime[entry.task()] = -1;
        processorOf[entry.task()] = -1;
        processorFreeAt[entry.processor()] = entry.previousFreeAt();
        makespan = entry.previousMakespan();
        taskCountOn[entry.processor()]--;

        // Restore the previous current bound
        currentBound = entry.previousBound();

        scheduledCount--;

        TaskGraph graph = ctx.getGraph();

        for (int k = graph.childStart(entry.task()); k < graph.childEnd(entry.task()); k++) {
            indegreeRemaining[graph.childAt(k)]++;
        }
    }


    /**
     * The template search method featuring the common shared bound/termination checks between the sequential
     * implementation and the parallel implementation. The actual branching strategy is not shared.
     */
    protected void search() {
        TaskGraph graph = ctx.getGraph();

        if (scheduledCount == graph.taskCount()) {
            // A leaf: this is the only place a new best can be found, and so the only place the
            // GUI is told about one. Everything else the panel shows is sampled on a timer.
            ctx.compareAndSetBestSchedule(makespan, startTime, processorOf);
            countExplored();
            return;
        }

        countExplored();

        if (lowerBound() >= ctx.getBest()) {
            // Lower bound pruning
            localPruned++;
            return;
        }

        for (int task = 0; task < graph.taskCount(); task++) {
            if (processorOf[task] == -1 && indegreeRemaining[task] == 0) {
                exploreProcessors(task);
            }
        }
    }

    /** Counts one explored branch locally, flushing the batch to the shared counters when full. */
    private void countExplored() {
        if (++localExplored >= COUNTER_FLUSH_INTERVAL) {
            flushCounters();
        }
    }

    /**
     * Pushes this search's locally accumulated branch counts to the shared metrics. Called
     * automatically when a batch fills, and once more when the search finishes so the trailing
     * partial batch is never lost.
     */
    public final void flushCounters() {
        if (localExplored != 0) {
            ctx.getMetrics().addBranchesExplored(localExplored);
            localExplored = 0;
        }
        if (localPruned != 0) {
            ctx.getMetrics().addBranchesPruned(localPruned);
            localPruned = 0;
        }
    }

    /**
     * Processor symmetry: empty processors are interchangeable, so scheduling a task onto the
     * second empty processor produces a schedule identical to the first up to relabeling. Only
     * the first empty processor is worth exploring, and every processor after it can be skipped.
     *
     * @return the exclusive upper bound on processors worth exploring for the current state.
     */
    protected final int processorLimit() {
        int numProcessors = ctx.getNumProcessors();

        for (int processor = 0; processor < numProcessors; processor++) {
            if (taskCountOn[processor] == 0) return processor + 1;
        }
        return numProcessors;
    }

    /**
     * Explores processors sequentially for a given task in-place.
     *
     * @param task The task to schedule
     * @param fromProcessor The starting processor (inclusive)
     * @param toProcessor The ending processor (exclusive)
     */
    protected final void exploreSequentially(int task, int fromProcessor, int toProcessor) {
        for (int processor = fromProcessor; processor < toProcessor; processor++) {
            boolean isEmpty = taskCountOn[processor] == 0;

            place(task, processor);
            search();
            undo();

            if (isEmpty) break; // All remaining processors would produce the same schedule so break.
        }
    }

    /**
     * For a ready task, explore the candidate processors
     */
    protected abstract void exploreProcessors(int task);

    @Override
    protected void compute() {
        try {
            search();
        } finally {
            flushCounters();
        }
    }
}
