package se306.scheduler.algorithm.core;

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
     * Records one branch that was taken: the placement was made and the resulting node was not
     * cut by the bound on entry. Flushes to the shared metrics once the local batch fills up.
     */
    public void countExplored() { if (++localExplored >= COUNTER_FLUSH_INTERVAL) flush(); }

    /**
     * Records one branch that was cut, whether before the placement was made or by the stronger
     * bound check at the child node's entry.
     *
     * <p>This flushes on the same interval as {@link #countExplored}. It has to: when pruning is
     * working well there are many cut branches per taken one, so a counter that only flushed on
     * the explored path would be the fastest-moving number and the least often published.
     */
    public void countPruned() { if (++localPruned >= COUNTER_FLUSH_INTERVAL) flush(); }

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
