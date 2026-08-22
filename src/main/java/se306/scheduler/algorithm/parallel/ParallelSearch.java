package se306.scheduler.algorithm.parallel;

import se306.scheduler.algorithm.AbstractSearch;
import se306.scheduler.algorithm.SearchContext;
import se306.scheduler.graph.TaskGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RecursiveAction;

public class ParallelSearch extends AbstractSearch {

    // A threshold helps worker threads decide whether to split a task further or run it sequentially.
    // It measures extra local tasks compared to available stealing threads. Exceeding this limit stops extra task
    // creation to prevent overhead.
    private static final int SURPLUS_THRESHOLD = 3;

    public ParallelSearch(SearchContext ctx) {
        super(ctx);
    }

    public ParallelSearch(ParallelSearch parent) {
        super(parent);
    }

    @Override
    protected void exploreProcessors(int task) {
        int numProcessors = ctx.getNumProcessors();
        List<ParallelSearch> forked = new ArrayList<>(numProcessors - 1);

        for (int processor = 1; processor < numProcessors; processor++) {
            if (getSurplusQueuedTaskCount() <= SURPLUS_THRESHOLD) {
                ParallelSearch child = new ParallelSearch(this);
                child.place(task, processor);
                child.fork();
                forked.add(child);
            } else {
                exploreSequentially(task, processor, processor + 1);
            }
        }

        exploreSequentially(task, 0, 1);

        for (ParallelSearch child : forked) {
            child.join();
        }
    }
}
