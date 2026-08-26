package se306.scheduler.gui;

import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.util.StringConverter;
import se306.scheduler.gui.MetricsHistory.Frame;
import se306.scheduler.gui.MetricsHistory.Improvement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * The convergence chart in the middle of {@link MetricsPanel}: best-makespan-so-far as a
 * staircase over time, falling back to a branches-explored view when the search hasn't
 * improved on its first schedule (the normal case on a single processor).
 *
 * <p>Also handles hover: mapping the cursor to the nearest sampled frame and drawing the
 * crosshair/dot for it. {@code onHover}/{@code onHoverCleared} keep the panel's stat tiles
 * synced with whatever's under the cursor.
 */
class ConvergenceChartView extends BorderPane {

    /**
     * Cap on points drawn for the branches-over-time fallback. The staircase only has two points
     * per improvement so it's naturally small, but the fallback samples every frame, and at a
     * sub-second interval that's thousands of points redrawn several times a second. This only
     * strides down what gets drawn - the underlying history and the tiles stay full resolution.
     */
    private static final int MAX_PROGRESS_POINTS = 400;

    /**
     * How far past the latest point to extend the time axis when it needs extending. The axis
     * sits still until the line hits its right edge, then jumps out to this multiple of the
     * current time. Keeps the plot growing in steps instead of creeping outward on every sample
     * and dragging the line left.
     */
    private static final double TIME_AXIS_HEADROOM = 1.2;

    /** Smallest span the time axis will show, so an instant search still renders something readable. */
    private static final double MIN_TIME_AXIS_SPAN = 1.0;

    private final MetricsHistory history;
    private final DoubleSupplier currentUpto;
    private final Consumer<Frame> onHover;
    private final Runnable onHoverCleared;

    private final NumberAxis xAxis = new NumberAxis();
    private final NumberAxis yAxis = new NumberAxis();
    private final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);

    /** The staircase. Symbols are hidden in CSS - markerSeries draws the dots instead. */
    private final XYChart.Series<Number, Number> stepSeries = new XYChart.Series<>();

    /** One dot per improvement event, no connecting line. */
    private final XYChart.Series<Number, Number> markerSeries = new XYChart.Series<>();

    /** Dot at the leading edge of the line - wherever the search currently is. */
    private final XYChart.Series<Number, Number> currentMarkerSeries = new XYChart.Series<>();

    /** Vertical line under the cursor. Two points, no symbols. */
    private final XYChart.Series<Number, Number> crosshairSeries = new XYChart.Series<>();

    /** Dot where the cursor's time meets the line. */
    private final XYChart.Series<Number, Number> hoverDotSeries = new XYChart.Series<>();

    private final Label noDataLabel = new Label("Not enough recorded steps to show a convergence trend.");

    /** Which chart mode is showing, so the hover marker knows what its y-value means. */
    private boolean showingProgress;

    /**
     * Current right edge of the time axis. Only grows - that's what keeps it still between jumps
     * and stops it collapsing inward while scrubbing a finished run.
     */
    private double timeAxisUpper;

    /** Index of the frame under the cursor, or -1 when the cursor isn't over the plotted data. */
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
        // Same abbreviation as the tiles, so branch counts read "1.2 B" instead of ten digits.
        // Makespan values are small enough that formatCount just passes them through unchanged.
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
        // Series order fixes the CSS colour index: 0 staircase, 1 improvement dots, 2 current
        // marker, 3 crosshair, 4 hover dot.
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

    /** Wipes the chart state for a fresh search. */
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

    /** The frame under the cursor, if the pointer's over the plotted data. */
    Optional<Frame> hoveredFrame() {
        if (hoverIndex < 0 || hoverIndex >= history.frameCount()) {
            return Optional.empty();
        }
        return Optional.of(history.frame(hoverIndex));
    }

    /** Drops the hover and hands the readout back to whatever's otherwise shown. */
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
     * Draws the convergence staircase up to {@code upto} seconds, falling back to the
     * search-progress view (or the "not enough data" label) if nothing's improved yet.
     *
     * <p>Each improvement is two points: one extending the previous makespan horizontally up to
     * the moment of improvement, then one dropping to the new makespan. A final point carries the
     * current best out to {@code upto} - the flat line saying "still the best we've got" - capped
     * off by the current marker.
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
            // Nothing's improved yet - show that the search is at least doing work.
            plotSearchProgress(upto);
            return;
        }

        if (upto > lastTime) {
            step.add(new XYChart.Data<>(upto, lastMakespan));
        }

        showingProgress = false;
        chart.setTitle("Convergence");
        yAxis.setLabel("Makespan");
        // setAll() swaps each series in one atomic change rather than clear()+adds, so it can't
        // land half-applied mid-layout and leave an orphaned symbol node behind.
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
     * Fallback for a search that hasn't beaten its starting schedule yet - the normal case on a
     * single processor, where the greedy schedule is already optimal and makespan never moves.
     * Branch counts come straight from the frames, so this is a real sampled time series rather
     * than a line through improvement events.
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

        // Stride down for drawing only, keeping the last frame so the line ends where the
        // current marker sits.
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
     * Extends the time axis once the line actually reaches its edge, jumping out to
     * {@link #TIME_AXIS_HEADROOM} times the current time and holding there. Between jumps the
     * axis doesn't move, so the line stays put instead of sliding left every sample.
     *
     * <p>Never shrinking is also what makes replay stable: scrubbing back to 3s of a 90s run
     * keeps the full 90s axis, so the staircase stays where it was instead of stretching out
     * again as the slider moves.
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
     * Maps a cursor position to a time on the axis and hovers whichever frame was actually
     * sampled closest to it, so the tiles read out real memory/CPU/branch numbers rather than
     * anything interpolated.
     */
    private void hoverAtScenePosition(double sceneX, double sceneY) {
        List<Frame> frames = history.frames();
        if (frames.isEmpty() || getCenter() != chart) {
            return;
        }
        Point2D local = xAxis.sceneToLocal(sceneX, sceneY);
        double time = xAxis.getValueForDisplay(local.getX()).doubleValue();

        // Past the leading edge - which is most of the plot thanks to the axis headroom -
        // there's nothing sampled to show, so treat the cursor as off the data rather than
        // snapping it back onto the last frame.
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
     * Re-places the markers after a chart rebuild has shifted the axes underneath them: onto the
     * hovered frame if there is one, otherwise back onto the leading edge.
     */
    private void redrawMarkers() {
        List<Frame> frames = history.frames();
        if (hoverIndex >= 0 && hoverIndex < frames.size()) {
            updateHoverMarker(frames.get(hoverIndex));
        } else if (!frames.isEmpty()) {
            moveCrosshairTo(currentUpto.getAsDouble());
        }
    }

    /** Stands the crosshair up at one time, spanning the plot's full height. */
    private void moveCrosshairTo(double timeSeconds) {
        crosshairSeries.getData().setAll(List.of(
                new XYChart.Data<>(timeSeconds, yAxis.getLowerBound()),
                new XYChart.Data<>(timeSeconds, yAxis.getUpperBound())));
    }

    /**
     * Draws the crosshair and the dot where the hovered frame sits. The dot sits on whatever's
     * actually plotted - the staircase in convergence mode, the branch curve in progress mode.
     */
    private void updateHoverMarker(Frame frame) {
        double time = frame.timeSeconds();
        moveCrosshairTo(time);

        if (!showingProgress && frame.bestMakespan() == Integer.MAX_VALUE) {
            // Nothing had been found yet at this point, so there's no line for the dot to sit on.
            hoverDotSeries.getData().clear();
            return;
        }
        Number value = showingProgress ? frame.branchesExplored() : frame.bestMakespan();
        hoverDotSeries.getData().setAll(List.of(new XYChart.Data<>(time, value)));
    }

    /** Binary search for whichever frame's sample time is closest to the given time. */
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