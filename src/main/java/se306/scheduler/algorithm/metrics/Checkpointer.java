package se306.scheduler.algorithm.metrics;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class Checkpointer {

    /**
     * A snapshot of search progress
     */
    public record Checkpoint(
            long branchesExplored,
            long branchesPruned,
            int bestMakespan,
            long elapsedNanos,
            long usedMemoryBytes,
            double cpuLoadPercent) {}

    private final List<Checkpoint> checkpoints = new ArrayList<>();

    private final long searchStartTime = System.nanoTime();

    private final Runtime runtime = Runtime.getRuntime();
    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    // Reduces the contention locking issues found with AtomicLong
    private final LongAdder branchesExplored = new LongAdder();
    private final LongAdder branchesPruned = new LongAdder();

    private final AtomicLong usedMemoryBytes = new AtomicLong();

    public void incrementBranchesExplored() {
        branchesExplored.increment();
    }

    public void incrementBranchesPruned() {
        branchesPruned.increment();
    }

    public long getBranchesExplored() { return branchesExplored.longValue(); }
    public long getBranchesPruned() { return branchesPruned.longValue(); }

    public synchronized void recordCheckpoint(int makespan) {
        checkpoints.add(buildCheckpoint(makespan));
    }

    /**
     * Forces one final checkpoint when the search completes, so a run that doesn't land exactly
     * on a checkpoint boundary still ends up with the true final state recorded.
     */
    public synchronized void recordFinalCheckpoint(int bestMakespan) {
        if (!checkpoints.isEmpty()
                && checkpoints.get(checkpoints.size() - 1).branchesExplored() == branchesExplored.longValue()) {
            return;
        }
        checkpoints.add(buildCheckpoint(bestMakespan));
    }

    private Checkpoint buildCheckpoint(int bestMakespan) {
        return new Checkpoint(
                branchesExplored.longValue(),
                branchesPruned.longValue(),
                bestMakespan,
                System.nanoTime() - searchStartTime,
                runtime.totalMemory() - runtime.freeMemory(),
                Math.max(0, osBean.getProcessCpuLoad() * 100));
    }

    public synchronized List<Checkpoint> getCheckpoints() { return List.copyOf(checkpoints); }

}
