package se306.scheduler.schedule;

import se306.scheduler.graph.TaskGraph;

/**
 * A complete assignment of every task to a processor and a start time — the search engine's result
 * (WBS 3.x) and the output writer's input (WBS 2.4).
 *
 * <p>Processors are numbered from 0 internally; {@code DotOutputWriter} emits them 1-based, which is
 * what the output format expects.
 */
public final class Schedule {

    private final int[] startTimes;
    private final int[] processors;

    /** Takes ownership of the given arrays; callers must not retain or mutate them afterwards. */
    public Schedule(int[] startTimes, int[] processors) {
        if (startTimes.length != processors.length) {
            throw new IllegalArgumentException("startTimes and processors must have one entry per "
                    + "task (got " + startTimes.length + " and " + processors.length + ").");
        }
        this.startTimes = startTimes;
        this.processors = processors;
    }

    public int startTime(int task) {
        return startTimes[task];
    }

    /** Zero-based processor index that {@code task} runs on. */
    public int processor(int task) {
        return processors[task];
    }

    public int taskCount() {
        return startTimes.length;
    }

    /** Finish time of the last task, i.e. the value the search is minimising. */
    public int makespan(TaskGraph graph) {
        int end = 0;
        for (int t = 0; t < startTimes.length; t++) {
            end = Math.max(end, startTimes[t] + graph.weight(t));
        }
        return end;
    }

    /** Number of distinct processors actually used. */
    public int processorsUsed() {
        int highest = -1;
        for (int p : processors) {
            highest = Math.max(highest, p);
        }
        return highest + 1;
    }
}