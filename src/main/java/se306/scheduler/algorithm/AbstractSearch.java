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

    // Shared state context
    protected final SearchContext ctx;

    public int[] processorOf;
    public int[] startTime;
    public int[] processorFreeAt;
    protected int[] indegreeRemaining;
    protected int[] taskCountOn;

    protected int scheduledCount;
    protected int makespan;
    protected int currentBound;

    protected Deque<LogEntry> log;

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
        return Math.max(makespan, currentBound);
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
            // Update best (and record a checkpoint) before returning, so a checkpoint that
            // lands on this exact branch sees the makespan this call just found, not the
            // pre-update value.
            ctx.compareAndSetBestSchedule(makespan, startTime, processorOf);
            ctx.incrementBranchesExplored();
            return;
        }

        ctx.incrementBranchesExplored();

        if (lowerBound() >= ctx.getBest()) {
            // Lower bound pruning
            ctx.incrementBranchesPruned();
            return;
        }

        for (int task = 0; task < graph.taskCount(); task++) {
            if (processorOf[task] == -1 && indegreeRemaining[task] == 0) {
                exploreProcessors(task);
            }
        }
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
        search();
    }
}
