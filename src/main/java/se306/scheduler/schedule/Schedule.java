package se306.scheduler.schedule;

import se306.scheduler.graph.TaskGraph;

/* A complete assignment of every task in a TaskGraph to a start time and a processor*/
public final class Schedule {

    private final int[] startTime;
    private final int[] processor;
    private final int numProcessors;
    private final int makespan;

    /**
     * startTime/processor arrays are indexed the same way as the graph they were
     * built from.
     */
    public Schedule(TaskGraph graph, int[] startTime, int[] processor, int numProcessors) {
        if (startTime.length != graph.taskCount() || processor.length != graph.taskCount()) {
            throw new IllegalArgumentException(
                    "startTime and processor must have exactly one entry per task.");
        }
        this.startTime = startTime.clone();
        this.processor = processor.clone();
        this.numProcessors = numProcessors;

        // Validate each task's assignment and track the overall finish time (makespan)
        int finish = 0;
        for (int t = 0; t < graph.taskCount(); t++) {
            if (this.processor[t] < 0 || this.processor[t] >= numProcessors) {
                throw new IllegalArgumentException(
                        "task '" + graph.name(t) + "' assigned to invalid processor " + this.processor[t]
                                + " (numProcessors=" + numProcessors + ").");
            }
            if (this.startTime[t] < 0) {
                throw new IllegalArgumentException(
                        "task '" + graph.name(t) + "' has negative start time " + this.startTime[t] + ".");
            }
            finish = Math.max(finish, this.startTime[t] + graph.weight(t));
        }
        this.makespan = finish;
    }

    /** Start time of the given task. */
    public int startTime(int task) {
        return startTime[task];
    }

    /** Processor the given task is scheduled on. */
    public int processor(int task) {
        return processor[task];
    }

    /** Number of processors this schedule was built for. */
    public int numProcessors() {
        return numProcessors;
    }

    /**
     * Finish time of the last task to complete (the schedule's objective value).
     */
    public int makespan() {
        return makespan;
    }

    /** Number of tasks in this schedule. */
    public int taskCount() {
        return startTime.length;
    }

    @Override
    public String toString() {
        return "Schedule[" + taskCount() + " tasks, " + numProcessors + " processors, makespan="
                + makespan + "]";
    }
}
