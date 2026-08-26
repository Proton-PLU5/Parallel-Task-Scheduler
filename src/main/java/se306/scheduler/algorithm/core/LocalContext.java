package se306.scheduler.algorithm.core;

import se306.scheduler.graph.TaskGraph;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public class LocalContext {

    protected int[] processorOf;
    protected int[] startTime;
    protected int[] processorFreeAt;
    protected int[] indegreeRemaining;
    protected int[] taskCountOn;
    protected int scheduledCount;
    protected int makespan;

    // Critical Path Bound
    protected int currentBound;

    // The task placed most recently on this DFS path, or -1 at the root.
    protected int lastPlaced = -1;

    // Total idle time committed so far across all processors.
    protected int idleTime;

    protected Deque<LogEntry> log;

    public LocalContext(int taskCount, int numProcessors, TaskGraph taskGraph) {
        this.processorOf = new int[taskCount];
        this.startTime = new int[taskCount];
        this.processorFreeAt = new int[numProcessors];
        this.indegreeRemaining = new int[taskCount];
        this.taskCountOn = new int[numProcessors];


        // Populate initial values
        Arrays.fill(processorOf, -1);
        Arrays.fill(startTime, -1);
        for (int t = 0; t < taskCount; t++) {
            indegreeRemaining[t] = taskGraph.parentCount(t);
        }

        this.log = new ArrayDeque<>();
    }

    // Create a new LocalContext that is a clone of the given parent context.
    // This is used when branching in the search tree.
    public LocalContext(LocalContext parentContext) {
        this.processorOf = parentContext.processorOf.clone();
        this.startTime = parentContext.startTime.clone();
        this.processorFreeAt = parentContext.processorFreeAt.clone();
        this.indegreeRemaining = parentContext.indegreeRemaining.clone();
        this.taskCountOn = parentContext.taskCountOn.clone();

        this.scheduledCount = parentContext.scheduledCount;
        this.makespan = parentContext.makespan;
        this.currentBound = parentContext.currentBound;
        this.lastPlaced = parentContext.lastPlaced;
        this.idleTime = parentContext.idleTime;

        // Create a fresh log, this clone is a new branch, so it never needs to undo
        // state it inherited from its parent.
        this.log = new ArrayDeque<>();
    }

    public void push(int task, int processor) {
        LogEntry entry = new LogEntry(task, processor, processorFreeAt[processor], makespan, currentBound, lastPlaced);
        this.log.push(entry);
    }

    public void decrementIdleTime(int ready, int processor) {
        idleTime += ready - processorFreeAt[processor];
    }

    public void updateCurrentBound(int newBound) {
        this.currentBound = Math.max(currentBound, newBound);
    }

    public LogEntry pop() {
        return log.pop();
    }

    /**
     * Places a task into the partial schedule by populating it with the new timestamps after the
     * task has been added, as well as creates a log entry to undo the scheduling
     * when we are backtracking.
     *
     * @param task The task to be placed
     * @param processor The processor the task should be scheduled onto.
     */
    void place(int task, int processor, int ready, SearchContext ctx) {
        TaskGraph graph = ctx.getGraph();

        // Log the previous state for backtracking
        push(task, processor);

        // The gap between the processor falling free and this task starting is committed
        // idle time: appends can never reach back before processorFreeAt to fill it.
        decrementIdleTime(ready, processor);

        // Update the state with this task scheduled
        processorOf[task] = processor;
        startTime[task] = ready;
        processorFreeAt[processor] = ready + graph.weight(task);
        taskCountOn[processor]++;
        makespan = Math.max(makespan, processorFreeAt[processor]);

        // Update the current bound
        updateCurrentBound(ready + ctx.getBottomLevel(task));

        lastPlaced = task;
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
    public void undo(SearchContext ctx) {
        TaskGraph graph = ctx.getGraph();
        LogEntry entry = pop();

        // Reclaim the idle gap this placement committed (its start minus when the processor fell free).
        idleTime -= startTime[entry.task()] - entry.previousFreeAt();

        startTime[entry.task()] = -1;
        processorOf[entry.task()] = -1;
        processorFreeAt[entry.processor()] = entry.previousFreeAt();
        makespan = entry.previousMakespan();
        taskCountOn[entry.processor()]--;

        // Restore the previous current bound
        currentBound = entry.previousBound();

        lastPlaced = entry.previousLastPlaced();
        scheduledCount--;

        for (int k = graph.childStart(entry.task()); k < graph.childEnd(entry.task()); k++) {
            indegreeRemaining[graph.childAt(k)]++;
        }
    }

    public int getMakespan() {
        return makespan;
    }

    public int[] getProcessorOf() {
        return processorOf;
    }

    public int[] getStartTime() {
        return startTime;
    }

    public int[] getProcessorFreeAt() {
        return processorFreeAt;
    }

    public int[] getIndegreeRemaining() {
        return indegreeRemaining;
    }

    public int[] getTaskCountOn() {
        return taskCountOn;
    }

    public int getScheduledCount() {
        return scheduledCount;
    }

    public int getCurrentBound() {
        return currentBound;
    }

    public int getLastPlaced() {
        return lastPlaced;
    }

    public int getIdleTime() {
        return idleTime;
    }

    public Deque<LogEntry> getLog() {
        return log;
    }

}
