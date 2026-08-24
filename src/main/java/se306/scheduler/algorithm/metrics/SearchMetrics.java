package se306.scheduler.algorithm.metrics;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.LongAdder;

/**
 * Live counters for the search, plus an on-demand snapshot of process-level statistics.
 *
 * <p>Nothing is stored here: this class holds no history at all. The GUI samples it on a timer
 * (see {@code MainWindow}) and keeps whatever history it needs on its own side, so a headless run
 * pays for nothing beyond two {@link LongAdder}s. The expensive parts - reading process CPU load
 * and heap usage - only ever happen inside {@link #snapshot()}, which the search itself never
 * calls, so they can never land on a worker thread or inside a lock.
 */
public class SearchMetrics {

    /** A point-in-time reading of search progress, built on demand and never retained here. */
    public record Snapshot(
            long branchesExplored,
            long branchesPruned,
            long elapsedNanos,
            long usedMemoryBytes,
            double cpuLoadPercent) {}

    private final long searchStartTime = System.nanoTime();

    private final Runtime runtime = Runtime.getRuntime();
    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    // LongAdder rather than AtomicLong: these are written from every worker thread and read
    // roughly once a second, which is exactly the trade LongAdder is built for.
    private final LongAdder branchesExplored = new LongAdder();
    private final LongAdder branchesPruned = new LongAdder();

    /**
     * Adds a batch of explored branches. Searches accumulate locally and flush in batches rather
     * than incrementing per node - see {@code AbstractSearch#countExplored()}.
     */
    public void addBranchesExplored(long count) {
        branchesExplored.add(count);
    }

    /** Adds a batch of pruned branches. Flushed alongside {@link #addBranchesExplored(long)}. */
    public void addBranchesPruned(long count) {
        branchesPruned.add(count);
    }

    public long getBranchesExplored() { return branchesExplored.longValue(); }
    public long getBranchesPruned() { return branchesPruned.longValue(); }

    /** Seconds since this context (and therefore the search) was created. */
    public double elapsedSeconds() {
        return (System.nanoTime() - searchStartTime) / 1_000_000_000.0;
    }

    /** Builds a reading of the current state. Called from the GUI thread, roughly once a second. */
    public Snapshot snapshot() {
        return new Snapshot(
                branchesExplored.longValue(),
                branchesPruned.longValue(),
                System.nanoTime() - searchStartTime,
                runtime.totalMemory() - runtime.freeMemory(),
                Math.max(0, osBean.getProcessCpuLoad() * 100));
    }
}
