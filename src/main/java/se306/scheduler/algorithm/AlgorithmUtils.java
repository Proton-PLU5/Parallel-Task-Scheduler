package se306.scheduler.algorithm;

import se306.scheduler.algorithm.core.LocalContext;
import se306.scheduler.algorithm.core.SearchContext;
import se306.scheduler.graph.TaskGraph;

public class AlgorithmUtils {

    private final LocalContext localContext;
    private final SearchContext searchContext;

    public AlgorithmUtils(LocalContext localContext, SearchContext searchContext) {
        this.localContext = localContext;
        this.searchContext = searchContext;
    }

    /**
     * The earliest time the task could start on the processor given the current partial
     * schedule. The earliest time can be no earlier than when processor is free,
     * and no earlier than every predecessor has finished
     * (plus comm cost if the predecessor ran on a different processor).
     */
    public int earliestStart(int task, int processor) {
        // Get the time when that processor is free.
        int ready = localContext.getProcessorFreeAt()[processor];

        TaskGraph graph = searchContext.getGraph();

        // Check all predecessors of the task to see when they finish and if there is a communication cost.
        for (int k = graph.parentStart(task); k < graph.parentEnd(task); k++) {
            // The predecessor task.
            int pred = graph.parentAt(k);

            // Determine when the predecessor finishes.
            int predFinish = localContext.getStartTime()[pred] + graph.weight(pred);

            // Check if the predecessor is on the same processor or on a different branch.
            // If on the same then comms cost is 0, else then get the comms cost.
            int comm = (localContext.getProcessorOf()[pred] == processor) ? 0 : graph.commCost(pred, task);

            // Determine the earliest time possible for the task to start on the processor
            // which is the maximum value of when the processor is free and
            // when all predecessors have finished (including comms cost).
            ready = Math.max(ready, predFinish + comm);
        }
        return ready;
    }

    /**
     * Returns the current lower bound on the final makespan for this partial schedule.
     * This bound is used to prune branches of the search tree that cannot improve the
     * best makespan.
     *
     * The bound is the maximum of:
     * - The current makespan of the partial schedule,
     * - The existing branch-and-bound bound stored in the local context, and,
     * - The load-based lower bound for the remaining work.
     *
     * This value is used to decide whether the current branch can still improve on the best
     * schedule found so far.
     *
     * @return the best lower bound known for the current search state
     */
    public int lowerBound() {
        return Math.max(localContext.getMakespan(),
                Math.max(localContext.getCurrentBound(),
                        searchContext.getLoadBound(localContext.getIdleTime())));
    }

    /**
     * Determines the next ready task whose dependencies have already been scheduled.
     *
     * @return an integer representing the task.
     */
    public int nextReadyTask() {
        int taskCount = searchContext.getGraph().taskCount();

        // Check through all the tasks
        for (int task = 0; task < taskCount; task++) {
            // If the task has not been assigned to a processor
            // And if the predecessors have all been assigned as well.
            // This task is ready. Return the first one we find.
            if (localContext.getProcessorOf()[task] == -1 && localContext.getIndegreeRemaining()[task] == 0) return task;
        }

        // If no tasks could be found return -1.
        return -1;
    }

    /**
     * First Pruning Strategy: Processor symmetry
     * Empty processors are interchangeable, so scheduling a task onto the
     * second empty processor produces a schedule identical to the first up to relabeling. Only
     * the first empty processor is worth exploring, and every processor after it can be skipped.
     *
     * @return the exclusive upper bound on processors worth exploring for the current state.
     */
    public final int processorLimit() {
        int numProcessors = searchContext.getNumProcessors();

        // Find the first empty processor, since later empty processors are symmetric.
        for (int processor = 0; processor < numProcessors; processor++) {
            // Return an exclusive upper bound that includes this first empty processor.
            if (localContext.getTaskCountOn()[processor] == 0) return processor + 1;
        }

        // If none are empty, all processors remain worth exploring.
        return numProcessors;
    }

    /**
     * Second Pruning Strategy: Decision-order Duplicate Pruning
     *
     * Two placements are independent of the order they're made in when they land on
     * different processors and have no dependency edge between them, scheduling A then B
     * reaches the exact same state as scheduling B then A. Without pruning, the search
     * would explore both orderings as separate branches for every such pair.
     *
     * To eliminate the duplicate without losing coverage, only the ordering where the
     * lower-indexed task is placed first is kept (the "canonical" order). A branch is pruned
     * here only when the current task's index is lower than the last placed task's index. i.e. we're
     * about to violate that canonical order, and the other ordering already covers this state.
     *
     * @return true when placing task on processor now would recreate a state
     *         that the canonical ordering already explores in a different branch.
     */
    public final boolean isPermutationDuplicate(int task, int processor) {
        return localContext.getLastPlaced() != -1
                && task < localContext.getLastPlaced()
                && processor != localContext.getProcessorOf()[localContext.getLastPlaced()]
                && !searchContext.getGraph().hasEdge(localContext.getLastPlaced(), task);
    }

    /**
     * Checks whether placing task on processor would already lose, before
     * actually placing it.
     *
     * Even at its earliest possible start time, the task still needs at least
     * bottomLevel(task) more time to finish everything downstream of it. If that
     * alone already reaches the current best makespan, no completion of this branch can
     * beat it, the branch is doomed regardless of how the rest of the schedule turns out.
     *
     * @return true if this branch cannot possibly improve on the current best schedule.
     */
    public final boolean isDoomed(int task, int processor) {
        return earliestStart(task, processor) + searchContext.getBottomLevel(task) >= searchContext.getBest();
    }
}
