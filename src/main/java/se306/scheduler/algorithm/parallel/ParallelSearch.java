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
        // Processors at or past this bound are empty duplicates of the first empty one. The
        // break inside exploreSequentially cannot catch them here, because every call below
        // covers a single processor - so the range is bounded up front instead.
        int limit = processorLimit();
        List<ParallelSearch> forked = new ArrayList<>(limit - 1);

        for (int processor = 1; processor < limit; processor++) {
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
