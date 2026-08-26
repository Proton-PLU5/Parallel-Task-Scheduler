package se306.scheduler.algorithm;

import se306.scheduler.graph.TaskGraph;

public class AlgorithmUtils {

    private LocalContext localContext;
    private SearchContext searchContext;

    public AlgorithmUtils(LocalContext localContext, SearchContext searchContext) {
        this.localContext = localContext;
        this.searchContext = searchContext;
    }

    /**
     * The earliest time the task could start on the processor given the current partial
     * schedule: no earlier than the processor is free, and no earlier than every predecessor
     * has finished (plus comm cost if the predecessor ran on a different processor).
     */
    public int earliestStart(int task, int processor) {
        int ready = localContext.processorFreeAt[processor];

        TaskGraph graph = searchContext.getGraph();

        for (int k = graph.parentStart(task); k < graph.parentEnd(task); k++) {
            int pred = graph.parentAt(k);
            int predFinish = localContext.startTime[pred] + graph.weight(pred);
            int comm = (localContext.processorOf[pred] == processor) ? 0 : graph.commCost(pred, task);
            ready = Math.max(ready, predFinish + comm);
        }
        return ready;
    }

    public int lowerBound() {
        return Math.max(localContext.makespan,
                Math.max(localContext.currentBound,
                        searchContext.getLoadBound(localContext.idleTime)));
    }

    /**
     * Determines the next ready task whose dependencies have already been scheduled.
     *
     * @return an integer representing the task.
     */
    public int nextReadyTask() {
        int taskCount = searchContext.getGraph().taskCount();

        for (int task = 0; task < taskCount; task++) {
            if (localContext.processorOf[task] == -1 && localContext.indegreeRemaining[task] == 0) return task;
        }
        return -1;
    }
}
