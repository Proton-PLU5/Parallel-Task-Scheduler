package se306.scheduler.algorithm;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

/**
 * Implements a greedy list scheduler (Milestone 1) that produces a valid
 * schedule,
 * but not necessarily an optimal one. The algorithm processes tasks in
 * topological order
 * and assigns each task to the processor that allows for the earliest start
 * time,
 * considering processor availability and communication costs from predecessor
 * tasks.
 */
public final class ListScheduler {

    private final TaskGraph graph;
    private final int numProcessors;

    public ListScheduler(TaskGraph graph, int numProcessors) {
        if (numProcessors < 1) {
            throw new IllegalArgumentException("numProcessors must be at least 1, was " + numProcessors);
        }
        this.graph = graph;
        this.numProcessors = numProcessors;
    }

    public Schedule solve() {
        int n = graph.taskCount();
        int[] processorOf = new int[n]; // processor each task is assigned to
        int[] startTime = new int[n]; // start time of each task
        int[] processorFreeAt = new int[numProcessors]; // time each processor becomes free

        // Process tasks in topological order so all predecessors are scheduled first
        for (int task : graph.topologicalOrder()) {
            int bestProcessor = 0;
            int bestStart = Integer.MAX_VALUE;

            // Try each processor and find the one giving the earliest start time
            for (int p = 0; p < numProcessors; p++) {
                int ready = processorFreeAt[p];

                // Task can't start until all predecessors have finished (plus comm cost
                // if the predecessor ran on a different processor)
                for (int k = graph.parentStart(task); k < graph.parentEnd(task); k++) {
                    int pred = graph.parentAt(k);
                    int predFinish = startTime[pred] + graph.weight(pred);
                    int comm = (processorOf[pred] == p) ? 0 : graph.commCost(pred, task);
                    ready = Math.max(ready, predFinish + comm);
                }
                if (ready < bestStart) {
                    bestStart = ready;
                    bestProcessor = p;
                }
            }

            // Assign the task to the best processor found and update its free time
            processorOf[task] = bestProcessor;
            startTime[task] = bestStart;
            processorFreeAt[bestProcessor] = bestStart + graph.weight(task);
        }

        return new Schedule(graph, startTime, processorOf, numProcessors);
    }
}