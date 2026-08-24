package se306.scheduler.gui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import se306.scheduler.algorithm.metrics.SearchMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the progress of a running search, and replays it once it finishes.
 *
 * <p>Two independent streams feed this panel, and keeping them separate is the whole point of the
 * design:
 *
 * <ul>
 *   <li><b>Improvements</b> are events. One arrives, via {@link SearchListener}, each time the
 *       search reaches a leaf that beats the current best. They are sparse - typically a handful
 *       per run - and they are the only thing the algorithm pushes.
 *   <li><b>Frames</b> are samples. One is taken every {@link #SAMPLE_INTERVAL} by
 *       {@code MainWindow}, carrying branch counts, memory and CPU. They say nothing about when
 *       the search improved, only how it was doing at that moment.
 * </ul>
 *
 * <p>Both are keyed on seconds since the search started, which is what makes scrubbing and
 * hovering coherent: showing time t means showing the frame at t alongside the improvement
 * staircase clipped to t. Crucially, improvements are never drawn as if they were samples - the
 * line between two improvements is flat, because that is what actually happened, and the last one
 * extends flat to the current time rather than sloping towards a point that does not exist yet.
 */
public class MetricsPanel extends BorderPane {

    /** How often the search status is sampled. {@code MainWindow} drives its timer from this. */
    public static final Duration SAMPLE_INTERVAL = Duration.seconds(0.1);

    private static final double METER_HEIGHT = 10;

    /**
     * Above this many frames the history is halved and the effective interval doubled, so an
     * hours-long run degrades to coarser sampling instead of growing without bound.
     */
    private static final int MAX_FRAMES = 3600;

    /**
     * Upper cap on points drawn in the branches-over-time fallback. The staircase is naturally
     * tiny (two points per improvement), but that fallback has one point per frame, and at a
     * sub-second sample interval the chart would end up redrawing thousands of points many times
     * a second. Frames are strided down to this many for drawing only; the underlying history and
     * everything the tiles report stay at full resolution.
     */
    private static final int MAX_PROGRESS_POINTS = 400;

    /**
     * The time axis runs to this multiple of the latest time rather than stopping dead at it, so
     * the leading edge of the line always has room ahead of it instead of being pinned against
     * the right edge of the plot.
     */
    private static final double TIME_AXIS_HEADROOM = 1.2;

    private static final int[] PLAYBACK_SPEEDS = {1, 4, 16};

    /** A sampled reading of the search status. bestMakespan is the best known at that time. */
    private record Frame(
            double timeSeconds,
            long branchesExplored,
            long branchesPruned,
            long usedMemoryBytes,
            double cpuPercent,
            int bestMakespan) {}

    /** A point where the search found a strictly better schedule. */
    private record Improvement(double timeSeconds, int makespan) {}

    private final List<Frame> frames = new ArrayList<>();
    private final List<Improvement> improvements = new ArrayList<>();

    private double frameIntervalSeconds = SAMPLE_INTERVAL.toSeconds();
    private int currentBest = Integer.MAX_VALUE;
    private double lastImprovementTime;
    private boolean complete;

    /** The frame the tiles show when nothing is hovered, and the anchor the chart is drawn to. */
    private int displayedIndex;

    /** The frame under the cursor, or -1 when the cursor is not over the plotted data. */
    private int hoverIndex = -1;

    /** Which of the two chart modes is on screen, so the hover marker knows what its y means. */
    private boolean showingProgress;

    private final Label statusPill = new Label("● Running");
    private final Label timelineLabel = new Label("Live · 0.0 s");
    private final Button playButton = new Button("Play");
    private final Button speedButton = new Button("1x");
    private final Slider timeSlider = new Slider(0, 0, 0);

    private final Label branchesValue = new Label("0");
    private final Label bestValue = new Label("-");
    private final Label timeValue = new Label("0.0 s");
    private final Label memoryValue = new Label("0 MB");
    private final Label cpuValue = new Label("0%");
    private final Label meterValue = new Label("0 of 0 · 0.0%");

    private final Region meterFill = new Region();
    private final DoubleProperty meterFraction = new SimpleDoubleProperty(0);

    private final NumberAxis convergenceXAxis = new NumberAxis();
    private final NumberAxis convergenceYAxis = new NumberAxis();
    private final LineChart<Number, Number> convergenceChart = new LineChart<>(convergenceXAxis, convergenceYAxis);

    /** The staircase itself. Its symbols are hidden in CSS; the markers series draws them instead. */
    private final XYChart.Series<Number, Number> stepSeries = new XYChart.Series<>();

    /** One point per improvement event, drawn as a dot with no connecting line. */
    private final XYChart.Series<Number, Number> markerSeries = new XYChart.Series<>();

    /** A single dot at the leading edge of the line: where the search has got to right now. */
    private final XYChart.Series<Number, Number> currentMarkerSeries = new XYChart.Series<>();

    /** A vertical line under the cursor. Two points, no symbols. */
    private final XYChart.Series<Number, Number> crosshairSeries = new XYChart.Series<>();

    /** A single dot where the cursor's time meets the line. */
    private final XYChart.Series<Number, Number> hoverDotSeries = new XYChart.Series<>();

    private final Label noConvergenceDataLabel =
            new Label("Not enough recorded steps to show a convergence trend.");

    private Timeline playbackTimeline;
    private int speedIndex;

    public MetricsPanel() {
        getStyleClass().add("metrics-panel");
        statusPill.getStyleClass().addAll("metrics-status-pill", "metrics-status-pill-running");
        timelineLabel.getStyleClass().add("metrics-title");

        playButton.setDisable(true);
        playButton.setOnAction(e -> togglePlayback());
        speedButton.setDisable(true);
        speedButton.setOnAction(e -> cycleSpeed());

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(12, timelineLabel, headerSpacer, statusPill, playButton, speedButton);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        timeSlider.setDisable(true);
        timeSlider.setSnapToTicks(true);
        timeSlider.setMajorTickUnit(1);
        timeSlider.setBlockIncrement(1);
        timeSlider.setMaxWidth(Double.MAX_VALUE);
        timeSlider.valueProperty().addListener(
                (obs, oldVal, newVal) -> displayFrame((int) Math.round(newVal.doubleValue())));

        VBox topSection = new VBox(12, headerRow, timeSlider);
        BorderPane.setMargin(topSection, new Insets(0, 0, 20, 0));

        GridPane tileGrid = new GridPane();
        tileGrid.setHgap(32);
        tileGrid.setVgap(16);
        tileGrid.add(statTile("Branches explored", branchesValue), 0, 0);
        tileGrid.add(statTile("Current best (makespan)", bestValue), 1, 0);
        tileGrid.add(statTile("Time taken", timeValue), 0, 1);
        tileGrid.add(statTile("Memory usage", memoryValue), 1, 1);
        tileGrid.add(statTile("CPU usage", cpuValue), 0, 2);

        VBox leftColumn = new VBox(28, tileGrid, buildMeterSection());
        leftColumn.setPrefWidth(300);

        convergenceXAxis.setLabel("Time (s)");
        convergenceXAxis.setAutoRanging(false);
        convergenceYAxis.setLabel("Makespan");
        convergenceYAxis.setAutoRanging(false);
        convergenceChart.setTitle("Convergence");
        convergenceChart.setAnimated(false);
        convergenceChart.setLegendVisible(false);
        // Series order fixes the CSS colour index of each: 0 staircase, 1 improvement dots,
        // 2 current marker, 3 crosshair, 4 hover dot.
        convergenceChart.getData().setAll(
                List.of(stepSeries, markerSeries, currentMarkerSeries, crosshairSeries, hoverDotSeries));
        convergenceChart.getStyleClass().add("metrics-chart");
        BorderPane.setMargin(convergenceChart, new Insets(0, 0, 0, 32));

        convergenceChart.setOnMouseMoved(event -> hoverAtScenePosition(event.getSceneX(), event.getSceneY()));
        convergenceChart.setOnMouseDragged(event -> hoverAtScenePosition(event.getSceneX(), event.getSceneY()));
        convergenceChart.setOnMouseExited(event -> clearHover());

        noConvergenceDataLabel.getStyleClass().add("metrics-section-label");
        noConvergenceDataLabel.setAlignment(Pos.CENTER);
        noConvergenceDataLabel.setMaxWidth(Double.MAX_VALUE);
        noConvergenceDataLabel.setMaxHeight(Double.MAX_VALUE);
        BorderPane.setMargin(noConvergenceDataLabel, new Insets(0, 0, 0, 32));

        setTop(topSection);
        setLeft(leftColumn);
        setCenter(noConvergenceDataLabel);
    }

    private VBox buildMeterSection() {
        Label sectionLabel = new Label("Search space pruned");
        sectionLabel.getStyleClass().add("metrics-section-label");
        meterValue.getStyleClass().add("metrics-meter-value");

        Region meterSpacer = new Region();
        HBox.setHgrow(meterSpacer, Priority.ALWAYS);
        HBox meterHeader = new HBox(sectionLabel, meterSpacer, meterValue);

        Region meterTrack = new Region();
        meterTrack.getStyleClass().add("metrics-meter-track");
        meterTrack.setPrefHeight(METER_HEIGHT);
        meterTrack.setMaxWidth(Double.MAX_VALUE);

        meterFill.getStyleClass().add("metrics-meter-fill");
        meterFill.setPrefHeight(METER_HEIGHT);
        meterFill.setMaxWidth(Region.USE_PREF_SIZE);
        meterFill.prefWidthProperty().bind(meterTrack.widthProperty().multiply(meterFraction));
        StackPane.setAlignment(meterFill, Pos.CENTER_LEFT);

        return new VBox(8, meterHeader, new StackPane(meterTrack, meterFill));
    }

    private VBox statTile(String labelText, Label valueLabel) {
        Label label = new Label(labelText);
        label.getStyleClass().add("metrics-tile-label");
        valueLabel.getStyleClass().add("metrics-tile-value");
        return new VBox(4, label, valueLabel);
    }

    /** Clears any previous run's history so the panel can be reused for a fresh search. */
    public void beginRun() {
        stopPlayback();
        clearHover();
        frames.clear();
        improvements.clear();
        frameIntervalSeconds = SAMPLE_INTERVAL.toSeconds();
        currentBest = Integer.MAX_VALUE;
        lastImprovementTime = 0;
        displayedIndex = 0;
        complete = false;

        statusPill.setText("● Running");
        statusPill.getStyleClass().remove("metrics-status-pill-complete");
        if (!statusPill.getStyleClass().contains("metrics-status-pill-running")) {
            statusPill.getStyleClass().add("metrics-status-pill-running");
        }
        timelineLabel.setText("Live · 0.0 s");
        timeSlider.setDisable(true);
        timeSlider.setMax(0);
        timeSlider.setValue(0);
        playButton.setDisable(true);
        speedButton.setDisable(true);
        stepSeries.getData().clear();
        markerSeries.getData().clear();
        currentMarkerSeries.getData().clear();
        setCenter(noConvergenceDataLabel);
    }

    /**
     * Records a new best schedule. Must be called on the FX thread.
     *
     * <p>Two guards matter here. Improvements are only accepted if they strictly beat the best
     * seen so far: parallel workers notify after releasing the lock, so two of them can arrive in
     * the opposite order to the one in which they took effect, and a stale one must not be drawn
     * as a step upwards. Times are clamped to be non-decreasing for the same reason.
     */
    public void recordImprovement(double elapsedSeconds, int makespan) {
        if (makespan >= currentBest) {
            return;
        }
        currentBest = makespan;
        lastImprovementTime = Math.max(elapsedSeconds, lastImprovementTime);
        improvements.add(new Improvement(lastImprovementTime, makespan));
        refreshLive();
    }

    /** Records one sampled reading of the search status. Must be called on the FX thread. */
    public void recordFrame(double elapsedSeconds, SearchMetrics.Snapshot snapshot) {
        frames.add(new Frame(
                elapsedSeconds,
                snapshot.branchesExplored(),
                snapshot.branchesPruned(),
                snapshot.usedMemoryBytes(),
                snapshot.cpuLoadPercent(),
                currentBest));
        decimateIfNeeded();
        refreshLive();
    }

    /** Switches the panel out of live-follow and into scrubbable replay. */
    public void markComplete() {
        complete = true;
        statusPill.setText("✓ Complete");
        statusPill.getStyleClass().remove("metrics-status-pill-running");
        statusPill.getStyleClass().add("metrics-status-pill-complete");

        if (frames.isEmpty()) {
            return;
        }
        boolean scrubbable = frames.size() > 1;
        timeSlider.setMax(frames.size() - 1);
        timeSlider.setDisable(!scrubbable);
        playButton.setDisable(!scrubbable);
        speedButton.setDisable(!scrubbable);
        timeSlider.setValue(frames.size() - 1);
        displayFrame(frames.size() - 1);
    }

    /**
     * Halves the frame history once it grows past {@link #MAX_FRAMES}, keeping every second frame
     * and doubling the effective interval. Playback stays real-time because its pacing is derived
     * from frameIntervalSeconds rather than assumed to be one sample per second.
     */
    private void decimateIfNeeded() {
        if (frames.size() <= MAX_FRAMES) {
            return;
        }
        List<Frame> kept = new ArrayList<>(frames.size() / 2 + 1);
        for (int i = 0; i < frames.size(); i += 2) {
            kept.add(frames.get(i));
        }
        frames.clear();
        frames.addAll(kept);
        frameIntervalSeconds *= 2;
        // Every index just moved underneath the cursor and the slider, so drop the hover rather
        // than let it keep reporting a different moment than the one being pointed at.
        clearHover();
    }

    /** While the search runs the view pins itself to the newest frame and the slider stays locked. */
    private void refreshLive() {
        if (complete) {
            return;
        }
        if (frames.isEmpty()) {
            // An improvement can land before the first sample is taken; draw it anyway.
            updateChart(lastImprovementTime);
            return;
        }
        int last = frames.size() - 1;
        timeSlider.setMax(last);
        if ((int) Math.round(timeSlider.getValue()) == last) {
            // Already pinned to the newest frame, so setValue would be a no-op and the listener
            // would not fire - which is the case when an improvement arrives between samples.
            displayFrame(last);
        } else {
            timeSlider.setValue(last);
        }
    }

    private void displayFrame(int index) {
        if (frames.isEmpty()) {
            return;
        }
        displayedIndex = Math.max(0, Math.min(index, frames.size() - 1));
        Frame frame = frames.get(displayedIndex);

        // While live, an improvement newer than the last sample should show up immediately rather
        // than waiting for the next tick. During replay the frame's own time is the whole truth.
        double upto = complete ? frame.timeSeconds() : Math.max(frame.timeSeconds(), lastImprovementTime);

        updateChart(upto);

        // A live refresh must not yank the tiles out from under a cursor parked on an earlier
        // moment: while hovering, the hovered frame keeps ownership of the readout.
        if (hoverIndex >= 0 && hoverIndex < frames.size()) {
            showStats(frames.get(hoverIndex), true);
        } else {
            showStats(frame, false);
        }
    }

    /** Fills the tiles, the meter and the timeline label from one frame. */
    private void showStats(Frame frame, boolean hovering) {
        if (hovering) {
            timelineLabel.setText(String.format("Hover · %.1f s", frame.timeSeconds()));
        } else if (complete) {
            timelineLabel.setText(String.format("Replay · %.1f s of %.1f s",
                    frame.timeSeconds(), frames.get(frames.size() - 1).timeSeconds()));
        } else {
            timelineLabel.setText(String.format("Live · %.1f s",
                    Math.max(frame.timeSeconds(), lastImprovementTime)));
        }

        branchesValue.setText(String.format("%,d", frame.branchesExplored()));
        bestValue.setText(frame.bestMakespan() == Integer.MAX_VALUE ? "-" : String.format("%,d", frame.bestMakespan()));
        timeValue.setText(String.format("%.1f s", frame.timeSeconds()));
        memoryValue.setText(String.format("%,d MB", frame.usedMemoryBytes() / (1024 * 1024)));
        cpuValue.setText(String.format("%.1f%%", frame.cpuPercent()));

        long pruned = frame.branchesPruned();
        long explored = frame.branchesExplored();
        double fraction = explored == 0 ? 0 : pruned / (double) explored;
        meterFraction.set(fraction);
        meterValue.setText(String.format("%,d of %,d · %.1f%%", pruned, explored, fraction * 100));
    }

    /**
     * Draws the convergence staircase clipped to upto seconds.
     *
     * <p>Each improvement contributes two points: one extending the previous makespan horizontally
     * to the moment of the improvement, and one dropping to the new makespan at that same moment.
     * A final horizontal point carries the current best out to upto - the flat "still the best we
     * have" line running to the present - and the current marker caps it off.
     */
    private void updateChart(double upto) {
        List<XYChart.Data<Number, Number>> step = new ArrayList<>();
        List<XYChart.Data<Number, Number>> markers = new ArrayList<>();

        int lastMakespan = Integer.MAX_VALUE;
        double lastTime = 0;
        int minMakespan = Integer.MAX_VALUE;
        int maxMakespan = Integer.MIN_VALUE;

        for (Improvement improvement : improvements) {
            if (improvement.timeSeconds() > upto) {
                break;
            }
            if (!step.isEmpty()) {
                step.add(new XYChart.Data<>(improvement.timeSeconds(), lastMakespan));
            }
            step.add(new XYChart.Data<>(improvement.timeSeconds(), improvement.makespan()));
            markers.add(new XYChart.Data<>(improvement.timeSeconds(), improvement.makespan()));

            lastMakespan = improvement.makespan();
            lastTime = improvement.timeSeconds();
            minMakespan = Math.min(minMakespan, lastMakespan);
            maxMakespan = Math.max(maxMakespan, lastMakespan);
        }

        if (step.isEmpty()) {
            // Nothing has improved yet - fall back to showing that the search is doing work.
            plotSearchProgress(upto);
            return;
        }

        if (upto > lastTime) {
            step.add(new XYChart.Data<>(upto, lastMakespan));
        }

        showingProgress = false;
        convergenceChart.setTitle("Convergence");
        convergenceYAxis.setLabel("Makespan");
        // setAll() replaces each series in one atomic list change instead of clear() + N adds, so
        // it cannot land half-applied across an axis-rescale layout pass and leave an orphaned
        // symbol node behind.
        stepSeries.getData().setAll(step);
        markerSeries.getData().setAll(markers);
        currentMarkerSeries.getData().setAll(List.of(new XYChart.Data<>(upto, lastMakespan)));

        convergenceYAxis.setLowerBound(Math.max(0, minMakespan - 6));
        convergenceYAxis.setUpperBound(maxMakespan + 6);
        convergenceYAxis.setTickUnit(
                Math.max(1, (convergenceYAxis.getUpperBound() - convergenceYAxis.getLowerBound()) / 5.0));
        setTimeAxis(upto);
        setCenter(convergenceChart);
        redrawHoverMarker();
    }

    /**
     * The fallback view for a search that has not improved on its starting schedule yet - which is
     * the normal case on a single processor, where the greedy schedule is already optimal and the
     * makespan never moves. Branch counts come from the frames, so this is a genuine sampled time
     * series rather than a line through improvement events.
     */
    private void plotSearchProgress(double upto) {
        int available = 0;
        while (available < frames.size() && frames.get(available).timeSeconds() <= upto) {
            available++;
        }
        if (available < 2) {
            setCenter(noConvergenceDataLabel);
            return;
        }

        // Stride the frames down for drawing only, always keeping the last one so the line ends
        // where the current marker sits.
        int stride = Math.max(1, available / MAX_PROGRESS_POINTS);
        List<XYChart.Data<Number, Number>> data = new ArrayList<>(available / stride + 1);
        long maxBranches = 0;
        for (int i = 0; i < available; i += stride) {
            Frame frame = frames.get(i);
            data.add(new XYChart.Data<>(frame.timeSeconds(), frame.branchesExplored()));
            maxBranches = Math.max(maxBranches, frame.branchesExplored());
        }
        Frame lastFrame = frames.get(available - 1);
        if ((available - 1) % stride != 0) {
            data.add(new XYChart.Data<>(lastFrame.timeSeconds(), lastFrame.branchesExplored()));
            maxBranches = Math.max(maxBranches, lastFrame.branchesExplored());
        }

        showingProgress = true;
        convergenceChart.setTitle("Search Progress");
        convergenceYAxis.setLabel("Branches explored");
        stepSeries.getData().setAll(data);
        markerSeries.getData().clear();
        currentMarkerSeries.getData().setAll(
                List.of(new XYChart.Data<>(lastFrame.timeSeconds(), lastFrame.branchesExplored())));

        convergenceYAxis.setLowerBound(0);
        convergenceYAxis.setUpperBound(maxBranches + Math.max(1, maxBranches / 10));
        convergenceYAxis.setTickUnit(Math.max(1, convergenceYAxis.getUpperBound() / 5.0));
        setTimeAxis(upto);
        setCenter(convergenceChart);
        redrawHoverMarker();
    }

    /**
     * Runs the time axis out past the latest point by {@link #TIME_AXIS_HEADROOM}, so the line
     * grows into empty space rather than the axis ending exactly where the data does.
     */
    private void setTimeAxis(double upto) {
        double upper = Math.max(frameIntervalSeconds, upto * TIME_AXIS_HEADROOM);
        convergenceXAxis.setLowerBound(0);
        convergenceXAxis.setUpperBound(upper);
        convergenceXAxis.setTickUnit(upper / 5.0);
    }

    /**
     * Maps a cursor position to a time on the axis and shows the frame nearest to it. The tiles
     * are repointed at that frame, so hovering reads out the memory, CPU and branch counts that
     * were actually sampled at that moment rather than interpolating anything.
     */
    private void hoverAtScenePosition(double sceneX, double sceneY) {
        if (frames.isEmpty() || getCenter() != convergenceChart) {
            return;
        }
        Point2D local = convergenceXAxis.sceneToLocal(sceneX, sceneY);
        double time = convergenceXAxis.getValueForDisplay(local.getX()).doubleValue();

        // Past the leading edge - which is most of the plot, given the axis headroom - there is
        // nothing sampled to report, so the cursor counts as being off the data rather than being
        // snapped back onto the last frame.
        double latest = frames.get(frames.size() - 1).timeSeconds();
        if (time < 0 || time > Math.min(latest, currentUpto())) {
            clearHover();
            return;
        }

        int index = nearestFrame(time);
        if (index == hoverIndex) {
            return;
        }
        hoverIndex = index;
        Frame frame = frames.get(index);
        showStats(frame, true);
        updateHoverMarker(frame);
    }

    /** Drops the hover and hands the readout back to whichever frame the slider is on. */
    private void clearHover() {
        if (hoverIndex < 0) {
            return;
        }
        hoverIndex = -1;
        crosshairSeries.getData().clear();
        hoverDotSeries.getData().clear();
        if (!frames.isEmpty()) {
            showStats(frames.get(Math.min(displayedIndex, frames.size() - 1)), false);
        }
    }

    /** Re-places the hover marker after a chart rebuild has moved the axes underneath it. */
    private void redrawHoverMarker() {
        if (hoverIndex >= 0 && hoverIndex < frames.size()) {
            updateHoverMarker(frames.get(hoverIndex));
        }
    }

    /**
     * Draws the crosshair and the dot where the hovered time meets the line. The dot's height is
     * whatever the visible chart is plotting, so it lands on the staircase in convergence mode and
     * on the branch-count curve in progress mode.
     */
    private void updateHoverMarker(Frame frame) {
        double time = frame.timeSeconds();
        crosshairSeries.getData().setAll(List.of(
                new XYChart.Data<>(time, convergenceYAxis.getLowerBound()),
                new XYChart.Data<>(time, convergenceYAxis.getUpperBound())));

        if (!showingProgress && frame.bestMakespan() == Integer.MAX_VALUE) {
            // Nothing had been found yet at this moment, so there is no line for the dot to sit on.
            hoverDotSeries.getData().clear();
            return;
        }
        Number value = showingProgress ? frame.branchesExplored() : frame.bestMakespan();
        hoverDotSeries.getData().setAll(List.of(new XYChart.Data<>(time, value)));
    }

    /** The latest time currently drawn, which is what the hover is allowed to range over. */
    private double currentUpto() {
        if (frames.isEmpty()) {
            return lastImprovementTime;
        }
        Frame frame = frames.get(Math.min(displayedIndex, frames.size() - 1));
        return complete ? frame.timeSeconds() : Math.max(frame.timeSeconds(), lastImprovementTime);
    }

    /** Binary search for the frame whose sample time is closest to the given time. */
    private int nearestFrame(double timeSeconds) {
        int low = 0;
        int high = frames.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (frames.get(mid).timeSeconds() < timeSeconds) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        if (low > 0) {
            double after = frames.get(low).timeSeconds() - timeSeconds;
            double before = timeSeconds - frames.get(low - 1).timeSeconds();
            if (before < after) {
                return low - 1;
            }
        }
        return low;
    }

    /**
     * Steps the slider one frame at a time, one sampled interval per frame at 1x. A long run is
     * unwatchable at true real-time, hence the speed control.
     */
    private void togglePlayback() {
        if (playbackTimeline != null && playbackTimeline.getStatus() == Animation.Status.RUNNING) {
            stopPlayback();
            return;
        }
        if ((int) timeSlider.getValue() >= frames.size() - 1) {
            timeSlider.setValue(0);
        }
        startPlayback();
    }

    private void startPlayback() {
        Duration frameDelay = Duration.seconds(frameIntervalSeconds / PLAYBACK_SPEEDS[speedIndex]);
        playbackTimeline = new Timeline(new KeyFrame(frameDelay, event -> {
            int next = (int) timeSlider.getValue() + 1;
            if (next >= frames.size()) {
                stopPlayback();
                return;
            }
            timeSlider.setValue(next);
        }));
        playbackTimeline.setCycleCount(Animation.INDEFINITE);
        playbackTimeline.play();
        playButton.setText("Pause");
    }

    private void stopPlayback() {
        if (playbackTimeline != null) {
            playbackTimeline.stop();
        }
        playButton.setText("Play");
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % PLAYBACK_SPEEDS.length;
        speedButton.setText(PLAYBACK_SPEEDS[speedIndex] + "x");

        // Rebuild the timeline so a speed change takes effect mid-playback rather than at the end.
        if (playbackTimeline != null && playbackTimeline.getStatus() == Animation.Status.RUNNING) {
            playbackTimeline.stop();
            startPlayback();
        }
    }
}
