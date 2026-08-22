package se306.scheduler.algorithm;

import java.util.Deque;
import java.util.concurrent.RecursiveAction;

/**
 *
 * NOTE: Even for the sequential we still extend the recursive action, as the parallel implementation requires it
 *       doesn't cost much and still works as expected in the sequential scenario. This way we can avoid having to
 *       create a wrapper just for the recursive action.
 */
public class AbstractSearch extends RecursiveAction {

    @Override
    protected void compute() {

    }

    protected record LogEntry(int task, int processor, int previousFreeAt, int previousMakespan, int previousBound) {}

    protected int[] processorOf;
    protected int[] startTime;
    protected int[] processorFreeAt;
    protected int[] indegreeRemaining;

    protected int scheduledCount;
    protected int makespan;
    protected int currentBound;

    protected Deque<LogEntry> log;


}
