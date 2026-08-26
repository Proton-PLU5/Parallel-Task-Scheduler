package se306.scheduler.algorithm.core;

import java.util.concurrent.RecursiveAction;

import se306.scheduler.algorithm.AlgorithmUtils;
import se306.scheduler.graph.TaskGraph;

/**
 *
 * NOTE: Even for the sequential we still extend the recursive action, as the parallel implementation requires it
 *       doesn't cost much and still works as expected in the sequential scenario. This way we can avoid having to
 *       create a wrapper just for the recursive action. The sequential search never calls the fork() / join() methods
 *       itself so that part is just unused in that scenario.
 */
public abstract class AbstractSearch extends RecursiveAction {

    // Branch Counter for tracking branch metrics
    protected BranchCounter branchCounter;

    // Local state context
    public LocalContext localContext;

    // Shared state context
    protected final SearchContext ctx;

    // The algorithm utils class which holds many of pruning method implementations.
    public AlgorithmUtils utils;

    // Set by compute(): whether this task's root node was cut by the lower bound the moment it was
    // entered.
    private boolean prunedAtEntry;

    /**
     * The primary constructor
     *
     * @param ctx the shared state context object
     */
    protected AbstractSearch(SearchContext ctx) {
        this.ctx = ctx;
        int taskCount = ctx.getGraph().taskCount();

        // Create a local context to manage all local state variables.
        this.localContext = new LocalContext(taskCount, ctx.getNumProcessors(), ctx.getGraph());

        // Create a branch counter for counting branches explored.
        this.branchCounter = new BranchCounter(ctx.getMetrics());

        this.utils = new AlgorithmUtils(localContext, ctx);
    }

    /**
     * The secondary constructor, used for creating child search tasks that share the same context
     *
     * @param parent the parent AbstractSearch object
     */
    protected AbstractSearch(AbstractSearch parent) {
        this.ctx = parent.ctx;
        this.branchCounter = new BranchCounter(ctx.getMetrics());
        this.localContext = new LocalContext(parent.localContext);
        this.utils = new AlgorithmUtils(localContext, ctx);
    }


    /**
     * Place a task into the log
     * @param task the task to be placed
     * @param processor the processor it should be placed on
     */
    public void place(int task, int processor) {
        localContext.place(task, processor, utils.earliestStart(task, processor), ctx);
    }

    /**
     * The template search method featuring the common shared bound/termination checks between the sequential
     * implementation and the parallel implementation. The actual branching strategy is not shared.
     *
     * @return true when this node was cut by the lower bound on entry, meaning the branch that
     *         reached it was a dead end and its caller should count it as pruned.
     */
    protected boolean search() {
        TaskGraph graph = ctx.getGraph();

        // This checks whether we reached the leaf node.
        if (localContext.scheduledCount == graph.taskCount()) {
            // If so then, compare and update the best schedule.
            ctx.compareAndSetBestSchedule(localContext);
            return false;
        }

        if (utils.lowerBound() >= ctx.getBest()) {
            // Lower bound pruning: nothing below this node can beat the best, so the branch that
            // reached it is a dead end.
            return true;
        }

        // Visit ready tasks in descending bottom-level order: critical-path tasks first, so the
        // DFS reaches near-optimal schedules early and later branches prune against a tight best.
        for (int task : ctx.getTaskPriorityOrder()) {
            if (localContext.processorOf[task] == -1 && localContext.indegreeRemaining[task] == 0) {
                exploreProcessors(task);
            }
        }
        return false;
    }

    /**
     * Explores processors sequentially for a given task in-place.
     *
     * @param task The task to schedule
     * @param fromProcessor The starting processor (inclusive)
     * @param toProcessor The ending processor (exclusive)
     */
    protected final void exploreSequentially(int task, int fromProcessor, int toProcessor) {
        // Loop through all the possible processors we can schedule on
        for (int processor = fromProcessor; processor < toProcessor; processor++) {
            boolean isEmpty = localContext.taskCountOn[processor] == 0;

            // Each iteration generates exactly one branch, and classifies it exactly once below.
            if (utils.isPermutationDuplicate(task, processor)) {
                branchCounter.countPruned();
            } else {

                // If the earliest start time and the bottom level of the task is greater than
                // or equal to the best schedule found so far, then this branch cannot beat
                // the best schedule and can be pruned.
                if (utils.isDoomed(task, processor)) {
                    branchCounter.countPruned();
                } else {
                    int earliestStartTime = utils.earliestStart(task, processor);

                    // Create a new log entry for the current state.
                    localContext.place(task, processor, earliestStartTime, ctx);

                    // Search the subtree rooted at this placement and check if it leads to a dead end.
                    boolean deadEnd = search();

                    // Undo and restore the previous state from the log
                    localContext.undo(ctx);

                    // The check above only bounds on the earliest start; the stronger bound at the
                    // child's entry may still have cut it, in which case this branch was pruned too.
                    if (deadEnd) {
                        branchCounter.countPruned();
                    } else {
                        branchCounter.countExplored();
                    }
                }
            }

            if (isEmpty) break; // All remaining processors would produce the same schedule so break.
        }
    }

    /**
     * For a ready task, explore the candidate processors
     * Implemented by subclasses that extend this class.
     */
    protected abstract void exploreProcessors(int task);

    /**
     * Whether this task's root node was cut by the bound on entry. Valid once join returns.
     */
    public final boolean wasPrunedAtEntry() {
        return prunedAtEntry;
    }

    @Override
    protected void compute() {
        try {
            prunedAtEntry = search();
        } finally {
            branchCounter.flush();
        }
    }
}
