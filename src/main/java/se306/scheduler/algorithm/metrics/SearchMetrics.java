package se306.scheduler.algorithm.metrics;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.LongAdder;

/**
 * The data structure used to keep track of search metrics for the checkpoints.
 */
public class SearchMetrics {

    public record Snapshot(
            long branchesExplored,
            long branchesPruned,
            long elapsedNanos,
            long usedMemoryBytes,
            double cpuLoadPercent) {}

    private final long searchStartTime = System.nanoTime();

    // Used to get Device related metrics.
    private final Runtime runtime = Runtime.getRuntime();
    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    // LongAdder rather than AtomicLong: these are written from every worker thread and read
    // roughly once a second, which is exactly the trade LongAdder is built for.
    private final LongAdder branchesExplored = new LongAdder();
    private final LongAdder branchesPruned = new LongAdder();

    /**
     * Adds a batch of explored branches. Searches accumulate locally and flush in batches rather
     * than incrementing per node.
     *
     * @param count the number of explored branches to add to the tally.
     */
    public void addBranchesExplored(long count) {
        branchesExplored.add(count);
    }

    /**
     * Adds a batch of pruned branches.
     * @param count The number of branches pruned to add to the tally.
     */
    public void addBranchesPruned(long count) {
        branchesPruned.add(count);
    }

    public long getBranchesExplored() { return branchesExplored.longValue(); }
    public long getBranchesPruned() { return branchesPruned.longValue(); }

    /**
     * Seconds since the search was created.
     */
    public double elapsedSeconds() {
        return (System.nanoTime() - searchStartTime) / 1_000_000_000.0;
    }

    /**
     * Builds a reading of the current state. Called from the GUI thread, roughly once a second.
     *
     * @return returns a snapshot populated with the values.
     */
    public Snapshot snapshot() {
        return new Snapshot(
                branchesExplored.longValue(),
                branchesPruned.longValue(),
                System.nanoTime() - searchStartTime,
                runtime.totalMemory() - runtime.freeMemory(),
                Math.max(0, osBean.getProcessCpuLoad() * 100));
    }
}
