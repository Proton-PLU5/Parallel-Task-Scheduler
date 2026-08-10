package se306.scheduler.schedule;

import se306.scheduler.graph.TaskGraph;

import java.util.ArrayDeque;
import java.util.Deque;

public class DFSBranchAndBound {

    private final TaskGraph graph;
    private final int numProcessors;

    private int[] processorOf;
    private int[] startTime;
    private int[] processorFreeAt;
    private int[] indegreeRemaining;
    private int scheduledCount;
    private int makespan;

    private record LogEntry(int task, int processor, int previousFreeAt, int previousMakespan) {}
    private Deque<LogEntry> log;

    private int best;
    private Schedule bestSchedule;

    public DFSBranchAndBound(TaskGraph graph, int numProcessors) {
        if (numProcessors < 1) {
            throw new IllegalArgumentException("numProcessors must be at least 1, was " + numProcessors);
        }

        this.graph = graph;
        this.numProcessors = numProcessors;
        this.log = new ArrayDeque<>();
    }

    public Schedule solve() {
        int n = graph.taskCount();
        makespan = 0;
        bestSchedule = null;
        best = Integer.MAX_VALUE;

        processorOf = new int[n]; // processor each task is assigned to
        startTime = new int[n]; // start time of each task
        processorFreeAt = new int[numProcessors];
        indegreeRemaining = new int[n];

        // Populate initial values
        java.util.Arrays.fill(processorOf, -1);
        java.util.Arrays.fill(startTime, -1);

        for (int t = 0; t < n; t++) {
            indegreeRemaining[t] = graph.parentCount(t);
        }

        log = new ArrayDeque<>();

        search();
        return bestSchedule;
    }

    private void search() {
        if (scheduledCount == graph.taskCount()) {
            // When all tasks have been scheduled,
            // Check if the new schedule is better than the best we got,
            // If so then update the best schedule.
            if (makespan < best) {
                best = makespan;
                bestSchedule = new Schedule(graph, startTime, processorOf, numProcessors);
            }

            // Otherwise return
            return;
        }

        if (makespan >= best) {
            // Prune if it's slower than the best we have seen.
            return;
        }

        int task = nextReadyTask();
        if (task == -1) return;

        for (int processor = 0; processor < numProcessors; processor++) {
            place(task, processor);
            search();
            undo();
        }
    }

    private int nextReadyTask() {
        for (int task = 0; task < graph.taskCount(); task++) {
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

        // Log the previous state for backtracking
        log.push(new LogEntry(task, processor, processorFreeAt[processor], makespan));

        // Update the state with this task scheduled
        processorOf[task] = processor;
        startTime[task] = ready;
        processorFreeAt[processor] = ready + graph.weight(task);
        makespan = Math.max(makespan, processorFreeAt[processor]);
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
    private void undo() {
        LogEntry entry = log.pop();
        startTime[entry.task()] = -1;
        processorOf[entry.task()] = -1;
        processorFreeAt[entry.processor()] = entry.previousFreeAt();
        makespan = entry.previousMakespan();
        scheduledCount--;

        for (int k = graph.childStart(entry.task()); k < graph.childEnd(entry.task()); k++) {
            indegreeRemaining[graph.childAt(k)]++;
        }
    }
}
