package se306.scheduler.gui.metrics;

import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.util.StringConverter;
import se306.scheduler.gui.metrics.MetricsHistory.Frame;
import se306.scheduler.gui.metrics.MetricsHistory.Improvement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * Displays the best makespan over time and falls back to branch progress when no improvement occurs.
 *
 * <p>Also handles chart hovering and keeps the statistics in sync with the selected frame.
 */
class ConvergenceChartView extends BorderPane {

    /**
     * Upper cap on points drawn in the branches-over-time fallback. The staircase is naturally
     * tiny (two points per improvement), but that fallback has one point per frame, and at a
     * sub-second sample interval the chart would end up redrawing thousands of points many times
     * a second. Frames are strided down to this many for drawing only; the underlying history and
     * everything the tiles report stay at full resolution.
     */
    private static final int MAX_PROGRESS_POINTS = 400;

    /**
     * How far past the latest point the time axis is extended, each time it has to be extended at
     * all. The axis holds still until the line actually reaches its right edge and only then
     * jumps out to this multiple, so the plot grows in occasional steps rather than creeping
     * outwards on every sample and dragging the whole line leftwards with it.
     */
    private static final double TIME_AXIS_HEADROOM = 1.2;

    /** Smallest span the time axis will ever show, so a search that ends instantly is still readable. */
    private static final double MIN_TIME_AXIS_SPAN = 1.0;

    private final MetricsHistory history;
    private final DoubleSupplier currentUpto;
    private final Consumer<Frame> onHover;
    private final Runnable onHoverCleared;

    private final NumberAxis xAxis = new NumberAxis();
    private final NumberAxis yAxis = new NumberAxis();
    private final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);

    /** The main convergence line. */
    private final XYChart.Series<Number, Number> stepSeries = new XYChart.Series<>();

    /** Marks each improvement. */
    private final XYChart.Series<Number, Number> markerSeries = new XYChart.Series<>();

    /** Marks the current point in the search. */
    private final XYChart.Series<Number, Number> currentMarkerSeries = new XYChart.Series<>();

    /** Vertical line shown at the hovered time. */
    private final XYChart.Series<Number, Number> crosshairSeries = new XYChart.Series<>();

    /** Marks the hovered point. */
    private final XYChart.Series<Number, Number> hoverDotSeries = new XYChart.Series<>();

    private final Label noDataLabel = new Label("Not enough recorded steps to show a convergence trend.");

    /** Whether the chart is showing search progress instead of convergence. */
    private boolean showingProgress;

    /**
     * The current right-hand edge of the time axis. Only ever grows, which is what keeps the axis
     * still between jumps and stops it collapsing back inwards while a finished run is scrubbed.
     */
    private double timeAxisUpper;

    /** The frame under the cursor, or -1 when the cursor is not over the plotted data. */
    private int hoverIndex = -1;

    ConvergenceChartView(
            MetricsHistory history,
            DoubleSupplier currentUpto,
            Consumer<Frame> onHover,
            Runnable onHoverCleared) {
        this.history = history;
        this.currentUpto = currentUpto;
        this.onHover = onHover;
        this.onHoverCleared = onHoverCleared;

        xAxis.setLabel("Time (s)");
        xAxis.setAutoRanging(false);
        yAxis.setLabel("Makespan");
        yAxis.setAutoRanging(false);
        // Same abbreviation as the tiles, so branch-count ticks read "1.2 B" rather than ten
        // digits. Makespan-mode values are small and pass through formatCount unchanged.
        yAxis.setTickLabelFormatter(new StringConverter<Number>() {
            @Override
            public String toString(Number value) {
                return MetricsPanel.formatCount(Math.round(value.doubleValue()));
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });
        chart.setTitle("Convergence");
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        // Series order fixes the CSS colour index of each: 0 staircase, 1 improvement dots,
        // 2 current marker, 3 crosshair, 4 hover dot.
        chart.getData().setAll(
                List.of(stepSeries, markerSeries, currentMarkerSeries, crosshairSeries, hoverDotSeries));
        chart.getStyleClass().add("metrics-chart");

        chart.setOnMouseMoved(event -> hoverAtScenePosition(event.getSceneX(), event.getSceneY()));
        chart.setOnMouseDragged(event -> hoverAtScenePosition(event.getSceneX(), event.getSceneY()));
        chart.setOnMouseExited(event -> clearHover());

        noDataLabel.getStyleClass().add("metrics-section-label");
        noDataLabel.setAlignment(Pos.CENTER);
        noDataLabel.setMaxWidth(Double.MAX_VALUE);
        noDataLabel.setMaxHeight(Double.MAX_VALUE);

        setCenter(noDataLabel);
    }

    /** Clears any previous run's chart state, ready for a fresh search. */
    void reset() {
        hoverIndex = -1;
        stepSeries.getData().clear();
        markerSeries.getData().clear();
        currentMarkerSeries.getData().clear();
        crosshairSeries.getData().clear();
        hoverDotSeries.getData().clear();
        timeAxisUpper = 0;
        setCenter(noDataLabel);
    }

    /** The frame currently under the cursor, if the pointer is over the plotted data. */
    Optional<Frame> hoveredFrame() {
        if (hoverIndex < 0 || hoverIndex >= history.frameCount()) {
            return Optional.empty();
        }
        return Optional.of(history.frame(hoverIndex));
    }

    /** Drops the hover, if any, and hands the readout back to whichever frame is otherwise shown. */
    void clearHover() {
        if (hoverIndex < 0) {
            return;
        }
        hoverIndex = -1;
        hoverDotSeries.getData().clear();
        if (history.isEmpty()) {
            crosshairSeries.getData().clear();
            return;
        }
        onHoverCleared.run();
        moveCrosshairTo(currentUpto.getAsDouble());
    }

    /**
     * Draws the convergence staircase clipped to upto seconds, falling back to a search-progress
     * view (or the "not enough data" label) when nothing has improved yet.
     *
     * <p>Each improvement contributes two points: one extending the previous makespan horizontally
     * to the moment of the improvement, and one dropping to the new makespan at that same moment.
     * A final horizontal point carries the current best out to upto - the flat "still the best we
     * have" line running to the present - and the current marker caps it off.
     */
    void render(double upto) {
        List<XYChart.Data<Number, Number>> step = new ArrayList<>();
        List<XYChart.Data<Number, Number>> markers = new ArrayList<>();

        int lastMakespan = Integer.MAX_VALUE;
        double lastTime = 0;
        int minMakespan = Integer.MAX_VALUE;
        int maxMakespan = Integer.MIN_VALUE;

        for (Improvement improvement : history.improvements()) {
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
            // Show search progress when there are no improvements.
            plotSearchProgress(upto);
            return;
        }

        if (upto > lastTime) {
            step.add(new XYChart.Data<>(upto, lastMakespan));
        }

        showingProgress = false;
        chart.setTitle("Convergence");
        yAxis.setLabel("Makespan");
        // setAll() replaces each series in one atomic list change instead of clear() + N adds, so
        // it cannot land half-applied across an axis-rescale layout pass and leave an orphaned
        // symbol node behind.
        stepSeries.getData().setAll(step);
        markerSeries.getData().setAll(markers);
        currentMarkerSeries.getData().setAll(List.of(new XYChart.Data<>(upto, lastMakespan)));

        yAxis.setLowerBound(Math.max(0, minMakespan - 6));
        yAxis.setUpperBound(maxMakespan + 6);
        yAxis.setTickUnit(Math.max(1, (yAxis.getUpperBound() - yAxis.getLowerBound()) / 5.0));
        setTimeAxis(upto);
        setCenter(chart);
        redrawMarkers();
    }

    /**
     * The fallback view for a search that has not improved on its starting schedule yet - which is
     * the normal case on a single processor, where the greedy schedule is already optimal and the
     * makespan never moves. Branch counts come from the frames, so this is a genuine sampled time
     * series rather than a line through improvement events.
     */
    private void plotSearchProgress(double upto) {
        List<Frame> frames = history.frames();
        int available = 0;
        while (available < frames.size() && frames.get(available).timeSeconds() <= upto) {
            available++;
        }
        if (available < 2) {
            setCenter(noDataLabel);
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
        chart.setTitle("Search Progress");
        yAxis.setLabel("Branches explored");
        stepSeries.getData().setAll(data);
        markerSeries.getData().clear();
        currentMarkerSeries.getData().setAll(
                List.of(new XYChart.Data<>(lastFrame.timeSeconds(), lastFrame.branchesExplored())));

        yAxis.setLowerBound(0);
        yAxis.setUpperBound(maxBranches + Math.max(1, maxBranches / 10));
        yAxis.setTickUnit(Math.max(1, yAxis.getUpperBound() / 5.0));
        setTimeAxis(upto);
        setCenter(chart);
        redrawMarkers();
    }

    /**
     * Extends the time axis, but only once the line has actually reached the end of it - at which
     * point it jumps out to {@link #TIME_AXIS_HEADROOM} times the current time and holds there
     * again. Between jumps the axis is completely still, so the plotted line stays put instead of
     * sliding left on every sample.
     *
     * <p>The bound never shrinks, which is also what makes replay stable: scrubbing back to 3 s of
     * a 90 s run keeps the full 90 s of axis, so the staircase stays where it was drawn rather
     * than stretching out again as the slider moves.
     */
    private void setTimeAxis(double upto) {
        if (upto > timeAxisUpper) {
            timeAxisUpper = Math.max(MIN_TIME_AXIS_SPAN, upto * TIME_AXIS_HEADROOM);
        }
        xAxis.setLowerBound(0);
        xAxis.setUpperBound(timeAxisUpper);
        xAxis.setTickUnit(timeAxisUpper / 5.0);
    }

    /**
     * Maps a cursor position to a time on the axis and hovers the frame nearest to it, notifying
     * {@code onHover} so the tiles read out the memory, CPU and branch counts that were actually
     * sampled at that moment rather than interpolating anything.
     */
    private void hoverAtScenePosition(double sceneX, double sceneY) {
        List<Frame> frames = history.frames();
        if (frames.isEmpty() || getCenter() != chart) {
            return;
        }
        Point2D local = xAxis.sceneToLocal(sceneX, sceneY);
        double time = xAxis.getValueForDisplay(local.getX()).doubleValue();

        // Past the leading edge - which is most of the plot, given the axis headroom - there is
        // nothing sampled to report, so the cursor counts as being off the data rather than being
        // snapped back onto the last frame.
        double latest = frames.get(frames.size() - 1).timeSeconds();
        if (time < 0 || time > Math.min(latest, currentUpto.getAsDouble())) {
            clearHover();
            return;
        }

        int index = nearestFrame(time);
        if (index == hoverIndex) {
            return;
        }
        hoverIndex = index;
        Frame frame = frames.get(index);
        onHover.accept(frame);
        updateHoverMarker(frame);
    }

    /**
     * Re-places the markers after a chart rebuild has moved the axes underneath them: onto the
     * hovered frame if there is one, and back onto the leading edge if there is not.
     */
    private void redrawMarkers() {
        List<Frame> frames = history.frames();
        if (hoverIndex >= 0 && hoverIndex < frames.size()) {
            updateHoverMarker(frames.get(hoverIndex));
        } else if (!frames.isEmpty()) {
            moveCrosshairTo(currentUpto.getAsDouble());
        }
    }

    /** Draws the crosshair at the given time. */
    private void moveCrosshairTo(double timeSeconds) {
        crosshairSeries.getData().setAll(List.of(
                new XYChart.Data<>(timeSeconds, yAxis.getLowerBound()),
                new XYChart.Data<>(timeSeconds, yAxis.getUpperBound())));
    }

    /**
     * Draws the crosshair and the dot where the hovered time meets the line. The dot's height is
     * whatever the visible chart is plotting, so it lands on the staircase in convergence mode and
     * on the branch-count curve in progress mode.
     */
    private void updateHoverMarker(Frame frame) {
        double time = frame.timeSeconds();
        moveCrosshairTo(time);

        if (!showingProgress && frame.bestMakespan() == Integer.MAX_VALUE) {
            // No point to mark if no schedule has been found yet.
            hoverDotSeries.getData().clear();
            return;
        }
        Number value = showingProgress ? frame.branchesExplored() : frame.bestMakespan();
        hoverDotSeries.getData().setAll(List.of(new XYChart.Data<>(time, value)));
    }

    /** Finds the frame closest to the given time. */
    private int nearestFrame(double timeSeconds) {
        List<Frame> frames = history.frames();
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
}
