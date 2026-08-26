package se306.scheduler.algorithm;

import se306.scheduler.algorithm.metrics.SearchMetrics;

public class BranchCounter {

    /**
     * How many branches a search accumulates locally before pushing them to the shared counters.
     */
    private static final int COUNTER_FLUSH_INTERVAL = 1024;
    private final SearchMetrics metrics;

    // Branch counters that get flushed to the shared metrics in batches.
    private long localExplored;
    private long localPruned;

    public BranchCounter(SearchMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Increments the number of branches that is explored locally,
     * and flushing it to the metrics once it passes the threshold.
     */
    public void countExplored() { if (++localExplored >= COUNTER_FLUSH_INTERVAL) flush(); }
    public void countPruned() { localPruned++; }

    /**
     * Pushes this search's locally accumulated branch counts to the shared metrics.
     */
    public final void flush() {
        if (localExplored != 0) {
            metrics.addBranchesExplored(localExplored);
            localExplored = 0;
        }
        if (localPruned != 0) {
            metrics.addBranchesPruned(localPruned);
            localPruned = 0;
        }
    }
}
