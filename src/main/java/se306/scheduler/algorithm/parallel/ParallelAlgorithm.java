package se306.scheduler.algorithm.parallel;

import se306.scheduler.algorithm.Algorithm;
import se306.scheduler.algorithm.SearchContext;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.gui.SearchListener;
import se306.scheduler.schedule.Schedule;

import java.util.*;
import java.util.concurrent.ForkJoinPool;

public class ParallelAlgorithm implements Algorithm {

    private final SearchContext context;
    private final ForkJoinPool pool;

    public ParallelAlgorithm(TaskGraph graph, int numProcessors, int numThreads) {
        this.context = new SearchContext(graph, numProcessors);
        this.pool = new ForkJoinPool(numThreads);
    }

    public ParallelAlgorithm(TaskGraph graph, int numProcessors, int numThreads, SearchListener listener) {
        this.context = new SearchContext(graph, numProcessors, listener);
        this.pool = new ForkJoinPool(numThreads);
    }

    public Schedule solve() {
        context.runGreedyAlgorithm();

        ParallelSearch root = new ParallelSearch(context);
        pool.invoke(root);
        pool.shutdown();
        context.getCheckpointer().recordFinalCheckpoint(context.getBest());
        return context.getBestSchedule();
    }

    @Override
    public SearchContext getContext() {
        return context;
    }
}
