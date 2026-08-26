package se306.scheduler.algorithm.parallel;

import se306.scheduler.algorithm.Algorithm;
import se306.scheduler.algorithm.core.SearchContext;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.gui.SearchListener;
import se306.scheduler.schedule.Schedule;

import java.util.concurrent.ForkJoinPool;

public class ParallelAlgorithm implements Algorithm {

    private final SearchContext context;

    /**
     * Using a ForkJoinPool with work stealing design for the parallelization.
     */
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
        // Run the greedy algorithm to get a good first makespan to improve initial pruning rates.
        context.runGreedyAlgorithm();

        // Create the "root" search task and let it be run by the thread pool,
        // The task then will explore all possible branches by creating children
        // by itself.
        ParallelSearch root = new ParallelSearch(context);
        pool.invoke(root);

        // Reached the end of the search, all possible branches has either been pruned or explored.
        pool.shutdown();

        // Return the search results!
        return context.getBestSchedule();
    }

    @Override
    public SearchContext getContext() {
        return context;
    }
}
