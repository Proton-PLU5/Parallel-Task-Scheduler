package se306.scheduler.algorithm.parallel;

import se306.scheduler.graph.TaskGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RecursiveAction;

public class SearchTask extends RecursiveAction {

    private ParallelAlgorithm parallelAlgorithmn;
    private TaskGraph graph;

    private final int makespan;

    // Branch specific bound
    private final int bound;
    private final int scheduledCount;

    private final int[] startTime;
    private final int[] processorOf;
    private final int[] processorFreeAt;
    private final int[] indegreeRemaining;

    public SearchTask(
            ParallelAlgorithm algorithm,
            int makespan,
            int[] startTime,
            int[] processorOf,
            int[] processorFreeAt,
            int[] indegreeRemaining,
            int bound,
            int scheduledCount) {
        this.parallelAlgorithmn = algorithm;
        this.graph = algorithm.getGraph();

        this.makespan = makespan;
        this.startTime = startTime;
        this.processorOf = processorOf;
        this.processorFreeAt = processorFreeAt;
        this.indegreeRemaining = indegreeRemaining;

        this.bound = bound;
        this.scheduledCount = scheduledCount;
    }

    @Override
    protected void compute() {
        if (scheduledCount == graph.taskCount()) {
            // All tasks have been scheduled
            // Check if the new schedule is better than the best we got,
            // If so then update the best schedule.
            this.parallelAlgorithmn.compareAndSetBestSchedule(
                    makespan,
                    startTime,
                    processorOf
            );

            // Otherwise return
            return;
        }

        if (Math.max(makespan, bound) >= parallelAlgorithmn.getBest()) {
            // Pruned against current global best
            return;
        }

        int task = nextReadyTask();
        if (task == -1) return;

        if (scheduledCount < parallelAlgorithmn.getParallelDepthCutoff()) {
            List<SearchTask> children = new ArrayList<>(parallelAlgorithmn.getNumProcessors());
            for (int processor = 0; processor < parallelAlgorithmn.getNumProcessors(); processor++) {
                children.add(spawnChild(task, processor));
            }
            invokeAll(children);
        } else {
            // Past the cutoff, recurse sequentially in this thread instead of paying fork/join overhead all the way to the leaves.
            for (int processor = 0; processor < parallelAlgorithmn.getNumProcessors(); processor++) {
                spawnChild(task, processor).compute();
            }
        }
    }

    private SearchTask spawnChild(int task, int processor) {

        // Clone all the arrays so that they can be sent to the child.
        // We need to clone otherwise the children would have shared references
        // as in Java arrays are pass by reference not by value.
        int[] newProcessorOf = processorOf.clone();
        int[] newStartTime = startTime.clone();
        int[] newProcessorFreeAt = processorFreeAt.clone();
        int[] newIndegreeRemaining = indegreeRemaining.clone();

        int ready = newProcessorFreeAt[processor];

        // Determine when the earliest ready time is for this processor.
        // Task can't start until all predecessors have finished (plus comm cost
        // if the predecessor ran on a different processor)
        for (int k = graph.parentStart(task); k < graph.parentEnd(task); k++) {
            int pred = graph.parentAt(k);
            int predFinish = newStartTime[pred] + graph.weight(pred);
            int comm = (newProcessorOf[pred] == processor) ? 0 : graph.commCost(pred, task);
            ready = Math.max(ready, predFinish + comm);
        }

        // Update the arrays
        newProcessorOf[task] = processor;
        newStartTime[task] = ready;

        // Calculate the new processor free time.
        newProcessorFreeAt[processor] = ready + graph.weight(task);

        // Determine the makespan and new bound
        int newMakespan = Math.max(makespan, newProcessorFreeAt[processor]);
        int newBound = Math.max(bound, ready + parallelAlgorithmn.getBottomLevel(task));

        // Update the indegree of the children (this task has been scheduled so they don't need to wait on this one)
        for (int k = graph.childStart(task); k < graph.childEnd(task); k++) {
            newIndegreeRemaining[graph.childAt(k)]--;
        }

        // Return the new search task
        return new SearchTask(
                parallelAlgorithmn, newMakespan, newStartTime, newProcessorOf, newProcessorFreeAt,
                newIndegreeRemaining, newBound, scheduledCount + 1
        );
    }

    /**
     * Determines which task is ready to be scheduled, by checking to see if it's parents have already finished
     *
     * @return The next ready task or -1 if no task could be found.
     */
    private int nextReadyTask() {
        for (int t = 0; t < graph.taskCount(); t++) {
            if (processorOf[t] == -1 && indegreeRemaining[t] == 0) return t;
        }
        return -1;
    }
}
