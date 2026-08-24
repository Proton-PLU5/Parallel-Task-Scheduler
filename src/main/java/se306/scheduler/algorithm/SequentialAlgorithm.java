package se306.scheduler.algorithm;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.gui.SearchListener;
import se306.scheduler.schedule.Schedule;

import java.util.ArrayDeque;
import java.util.Deque;

public class SequentialAlgorithm extends AbstractSearch implements Algorithm {

    public SequentialAlgorithm(TaskGraph graph, int numProcessors) {
        super(new SearchContext(graph, numProcessors));
    }

    public SequentialAlgorithm(TaskGraph graph, int numProcessors, SearchListener listener) {
        super(new SearchContext(graph, numProcessors, listener));
    }

    @Override
    protected void exploreProcessors(int task) {
        exploreSequentially(task, 0, ctx.getNumProcessors());
    }

    @Override
    public Schedule solve() {
        ctx.runGreedyAlgorithm();
        search();
        ctx.getCheckpointer().recordFinalCheckpoint(ctx.getBest());
        return ctx.getBestSchedule();
    }

    @Override
    public SearchContext getContext() {
        return ctx;
    }
}
