package se306.scheduler.algorithm;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.RecursiveAction;

import se306.scheduler.graph.TaskGraph;

/**
 *
 * NOTE: Even for the sequential we still extend the recursive action, as the parallel implementation requires it
 *       doesn't cost much and still works as expected in the sequential scenario. This way we can avoid having to
 *       create a wrapper just for the recursive action. The sequential search never calls the fork() / join() methods
 *       itself so that part is just unused in that scenario.
 */
public abstract class AbstractSearch extends RecursiveAction {

    protected BranchCounter branchCounter;

    // Local state context
    public LocalContext localContext;

    // Shared state context
    protected final SearchContext ctx;

    // The algorithm utils class which holds many of pruning method implementations.
    public AlgorithmUtils utils;

    /**
     * The primary constructor
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
     */
    protected void search() {
        TaskGraph graph = ctx.getGraph();

        if (localContext.scheduledCount == graph.taskCount()) {
            // A leaf: this is the only place a new best can be found, and so the only place the
            // GUI is told about one. Everything else the panel shows is sampled on a timer.
            ctx.compareAndSetBestSchedule(localContext);
            branchCounter.countExplored();
            return;
        }

        branchCounter.countExplored();

        if (utils.lowerBound() >= ctx.getBest()) {
            // Lower bound pruning
            branchCounter.countPruned();
            return;
        }

        // Visit ready tasks in descending bottom-level order: critical-path tasks first, so the
        // DFS reaches near-optimal schedules early and later branches prune against a tight best.
        for (int task : ctx.getTaskPriorityOrder()) {
            if (localContext.processorOf[task] == -1 && localContext.indegreeRemaining[task] == 0) {
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
            boolean isEmpty = localContext.taskCountOn[processor] == 0;

            if (utils.isPermutationDuplicate(task, processor)) {
                branchCounter.countPruned();
            } else {
                int est = utils.earliestStart(task, processor);
                if (est + ctx.getBottomLevel(task) >= ctx.getBest()) {
                    branchCounter.countPruned(); // See isDoomed: this branch can't beat the best.
                } else {
                    localContext.place(task, processor, est, ctx);
                    search();
                    localContext.undo(ctx);
                }
            }

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
            branchCounter.flush();
        }
    }
}
