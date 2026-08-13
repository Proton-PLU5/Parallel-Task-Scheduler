package se306.scheduler.schedule.parallel;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

import java.util.concurrent.atomic.AtomicInteger;

public class SearchTask implements Runnable {

    private ParallelAlgorithmn parallelAlgorithmn;
    private TaskGraph graph;

    private int makespan;

    private int[] processorOf;
    private int[] startTime;
    private int[] processorFreeAt;
    private int[] indegreeRemaining;

    private int best;
    private int task;
    private int scheduledCount;

    public SearchTask(
            ParallelAlgorithmn algorithmn,
            int makespan,
            int[] startTime,
            int[] indegreeRemaining,
            int best,
            int task,
            int scheduledCount) {
        this.parallelAlgorithmn = algorithmn;
        this.makespan = makespan;
        this.startTime = startTime.clone();
        this.indegreeRemaining = indegreeRemaining.clone();
        this.best = best;
        this.task = task;
        this.graph = algorithmn.getGraph();
        this.scheduledCount = scheduledCount;
    }

    public boolean hasAllTasksBeenScheduled() {
        return this.scheduledCount == this.graph.taskCount();
    }

    @Override
    public void run() {
        if (hasAllTasksBeenScheduled()) {
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

        if (parallelAlgorithmn.getLowerBound() >= best) {
            // Lower bound pruning
            return;
        }

        if (task == -1) return;

        for (int processor = 0; processor < parallelAlgorithmn.getNumProcessors(); processor++) {
            place(task, processor);

            SearchTask searchTask = new SearchTask(
                    parallelAlgorithmn,
                    makespan,
                    startTime,
                    indegreeRemaining,
                    best,
                    task,
                    scheduledCount
            );

            parallelAlgorithmn.createNewThread(searchTask);
        }
    }

    private void place(int task, int processor) {
        int ready = processorFreeAt[processor];

        // Task can't start until all predecessors have finished (plus comm cost
        // if the predecessor ran on a different processor)
        for (int k = graph.parentStart(task); k < graph.parentEnd(task); k++) {
            int pred = graph.parentAt(k);
            int predFinish = startTime[pred] + graph.weight(pred);
            int comm = (processorOf[pred] == processor) ? 0 : graph.commCost(pred, task);
            ready = Math.max(ready, predFinish + comm);
        }

        // Update the state with this task scheduled
        processorOf[task] = processor;
        startTime[task] = ready;
        processorFreeAt[processor] = ready + graph.weight(task);
        makespan = Math.max(makespan, processorFreeAt[processor]);

        parallelAlgorithmn.updateBound(ready + parallelAlgorithmn.getBottomLevel(task));

        this.scheduledCount++;

        // Decrease indegree of children
        for (int k = graph.childStart(task); k < graph.childEnd(task); k++) {
            int child = graph.childAt(k);
            indegreeRemaining[child]--;
        }
    }
}
