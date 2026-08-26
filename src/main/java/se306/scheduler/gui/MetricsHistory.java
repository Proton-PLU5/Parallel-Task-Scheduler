package se306.scheduler.gui;

import javafx.util.Duration;
import se306.scheduler.algorithm.metrics.SearchMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records everything {@link MetricsPanel} needs to remember about a run: the sampled {@link
 * Frame}s and the {@link Improvement} events, kept as two independent streams for the reasons
 * described on {@link MetricsPanel}.
 */
class MetricsHistory {

    /**
     * Above this many frames the history is halved and the effective interval doubled, so an
     * hours-long run degrades to coarser sampling instead of growing without bound.
     */
    private static final int MAX_FRAMES = 3600;

    /** A sampled reading of the search status. bestMakespan is the best known at that time. */
    record Frame(
            double timeSeconds,
            long branchesExplored,
            long branchesPruned,
            long usedMemoryBytes,
            double cpuPercent,
            int bestMakespan) {}

    /** A point where the search found a strictly better schedule. */
    record Improvement(double timeSeconds, int makespan) {}

    private final List<Frame> frames = new ArrayList<>();
    private final List<Improvement> improvements = new ArrayList<>();

    private double frameIntervalSeconds;
    private int currentBest = Integer.MAX_VALUE;
    private double lastImprovementTime;

    MetricsHistory(Duration sampleInterval) {
        frameIntervalSeconds = sampleInterval.toSeconds();
    }

    /** Clears any previous run's history so the panel can be reused for a fresh search. */
    void reset(Duration sampleInterval) {
        frames.clear();
        improvements.clear();
        frameIntervalSeconds = sampleInterval.toSeconds();
        currentBest = Integer.MAX_VALUE;
        lastImprovementTime = 0;
        // Anchor the history at t=0 so hover data is valid from the very start.
        frames.add(new Frame(0, 0, 0, 0, 0.0, Integer.MAX_VALUE));
    }

    /**
     * Records a new best schedule.
     *
     * <p>Two guards matter here. Improvements are only accepted if they strictly beat the best
     * seen so far: parallel workers notify after releasing the lock, so two of them can arrive in
     * the opposite order to the one in which they took effect, and a stale one must not be drawn
     * as a step upwards. Times are clamped to be non-decreasing for the same reason.
     *
     * @return true if the improvement was accepted
     */
    boolean recordImprovement(double elapsedSeconds, int makespan) {
        if (makespan >= currentBest) {
            return false;
        }
        currentBest = makespan;
        lastImprovementTime = Math.max(elapsedSeconds, lastImprovementTime);
        improvements.add(new Improvement(lastImprovementTime, makespan));
        return true;
    }

    /**
     * Records one sampled reading of the search status.
     *
     * @return true if this pushed the history over {@link #MAX_FRAMES} and it was decimated,
     *     which invalidates any frame index held elsewhere (e.g. a hovered frame)
     */
    boolean recordFrame(double elapsedSeconds, SearchMetrics.Snapshot snapshot) {
        frames.add(new Frame(
                elapsedSeconds,
                snapshot.branchesExplored(),
                snapshot.branchesPruned(),
                snapshot.usedMemoryBytes(),
                snapshot.cpuLoadPercent(),
                currentBest));
        return decimateIfNeeded();
    }

    /**
     * Halves the frame history once it grows past {@link #MAX_FRAMES}, keeping every second frame
     * and doubling the effective interval. Playback stays real-time because its pacing is derived
     * from frameIntervalSeconds rather than assumed to be one sample per second.
     */
    private boolean decimateIfNeeded() {
        if (frames.size() <= MAX_FRAMES) {
            return false;
        }
        List<Frame> kept = new ArrayList<>(frames.size() / 2 + 1);
        for (int i = 0; i < frames.size(); i += 2) {
            kept.add(frames.get(i));
        }
        frames.clear();
        frames.addAll(kept);
        frameIntervalSeconds *= 2;
        return true;
    }

    List<Frame> frames() {
        return Collections.unmodifiableList(frames);
    }

    List<Improvement> improvements() {
        return Collections.unmodifiableList(improvements);
    }

    boolean isEmpty() {
        return frames.isEmpty();
    }

    int frameCount() {
        return frames.size();
    }

    Frame frame(int index) {
        return frames.get(index);
    }

    double frameIntervalSeconds() {
        return frameIntervalSeconds;
    }

    double lastImprovementTime() {
        return lastImprovementTime;
    }
}
