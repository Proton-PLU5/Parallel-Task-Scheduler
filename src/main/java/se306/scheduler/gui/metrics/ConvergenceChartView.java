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

    private static final int MAX_PROGRESS_POINTS = 400;

    private static final int MAX_INTEGER_TICKS = 24;
    private static final double TIME_AXIS_HEADROOM = 1.2;
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
    private double timeAxisUpper;

    /** The time under the cursor, or -1 when the cursor is not over the plotted data. */
    private double hoverTimeSeconds = -1;

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
        yAxis.setMinorTickVisible(false);
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

    void reset() {
        hoverTimeSeconds = -1;
        stepSeries.getData().clear();
        markerSeries.getData().clear();
        currentMarkerSeries.getData().clear();
        crosshairSeries.getData().clear();
        hoverDotSeries.getData().clear();
        timeAxisUpper = 0;
        setCenter(noDataLabel);
    }

    /** The (possibly interpolated) frame currently under the cursor, if it's over the plotted data. */
    Optional<Frame> hoveredFrame() {
        if (hoverTimeSeconds < 0 || history.isEmpty()) {
            return Optional.empty();
        }
        double dataTime = Math.max(earliestRealTime(), hoverTimeSeconds);
        return Optional.of(history.frameAt(dataTime));   // was: frameAt(dataTime)
    }

    void clearHover() {
        if (hoverTimeSeconds < 0) {
            return;
        }
        hoverTimeSeconds = -1;
        hoverDotSeries.getData().clear();
        if (history.isEmpty()) {
            crosshairSeries.getData().clear();
            return;
        }
        onHoverCleared.run();
        moveCrosshairTo(currentUpto.getAsDouble());
    }

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
        stepSeries.getData().setAll(step);
        markerSeries.getData().setAll(markers);
        currentMarkerSeries.getData().setAll(List.of(new XYChart.Data<>(upto, lastMakespan)));

        // One gridline per whole makespan, so the staircase and the live line always sit
        // exactly on a gridline. Only a range too tall for that to stay readable falls
        // back to a coarser integer unit.
        int lower = Math.max(0, minMakespan - 6);
        int upper = maxMakespan + 6;
        int tickUnit = 1;
        if (upper - lower > MAX_INTEGER_TICKS) {
            tickUnit = (int) Math.ceil((upper - lower) / (double) MAX_INTEGER_TICKS);
            lower = Math.max(0, lower / tickUnit * tickUnit);
            upper = (upper + tickUnit - 1) / tickUnit * tickUnit;
        }
        yAxis.setLowerBound(lower);
        yAxis.setUpperBound(upper);
        yAxis.setTickUnit(tickUnit);
        setTimeAxis(upto);
        setCenter(chart);
        redrawMarkers();
    }

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
        // Whole-number ticks for the same reason as the staircase: labels are rounded, so a
        // fractional gridline would be labelled with a count it does not sit at.
        yAxis.setTickUnit(Math.max(1, Math.ceil(yAxis.getUpperBound() / 5.0)));
        setTimeAxis(upto);
        setCenter(chart);
        redrawMarkers();
    }

    private void setTimeAxis(double upto) {
        if (upto > timeAxisUpper) {
            timeAxisUpper = Math.max(MIN_TIME_AXIS_SPAN, upto * TIME_AXIS_HEADROOM);
        }
        xAxis.setLowerBound(0);
        xAxis.setUpperBound(timeAxisUpper);
        xAxis.setTickUnit(timeAxisUpper / 5.0);
    }

    private void hoverAtScenePosition(double sceneX, double sceneY) {
        List<Frame> frames = history.frames();
        if (frames.isEmpty() || getCenter() != chart) {
            return;
        }
        Point2D local = xAxis.sceneToLocal(sceneX, sceneY);
        double time = xAxis.getValueForDisplay(local.getX()).doubleValue();

        double earliest = earliestRealTime();
        double latest = currentUpto.getAsDouble();   // was: frames.get(frames.size() - 1).timeSeconds()

        double clampedTime = Math.max(earliest, Math.min(latest, time));

        hoverTimeSeconds = clampedTime;
        Frame frame = history.frameAt(clampedTime);
        onHover.accept(frame);
        updateHoverMarker(clampedTime, frame);
    }

    private void updateHoverMarker(double dataTime, Frame frame) {
        moveCrosshairTo(hoverTimeSeconds);

        if (showingProgress) {
            hoverDotSeries.getData().setAll(
                    List.of(new XYChart.Data<>(hoverTimeSeconds, frame.branchesExplored())));
            return;
        }

        if (frame.bestMakespan() == Integer.MAX_VALUE) {
            hoverDotSeries.getData().clear();
            return;
        }
        hoverDotSeries.getData().setAll(
                List.of(new XYChart.Data<>(hoverTimeSeconds, frame.bestMakespan())));
    }

    /**
     * The earliest time the cursor should be able to rest on. Frame 0 is the synthetic t=0
     * anchor MetricsHistory adds for interpolation, not a real sample. On the convergence view
     * the actual first plotted point is an Improvement, which can land before the first sampled
     * Frame - so that, not frames.get(1), is the correct left bound there.
     */
    private double earliestRealTime() {
        if (!showingProgress && !history.improvements().isEmpty()) {
            return history.improvements().get(0).timeSeconds();
        }
        List<Frame> frames = history.frames();
        return frames.size() > 1 ? frames.get(1).timeSeconds() : frames.get(0).timeSeconds();
    }

    private void redrawMarkers() {
        if (hoverTimeSeconds >= 0 && !history.isEmpty()) {
            double dataTime = Math.max(earliestRealTime(), hoverTimeSeconds);
            Frame frame = history.frameAt(dataTime);
            onHover.accept(frame);
            updateHoverMarker(dataTime, frame);
        } else if (!history.frames().isEmpty()) {
            moveCrosshairTo(currentUpto.getAsDouble());
        }
    }

    /** Draws the crosshair at the given time. */
    private void moveCrosshairTo(double timeSeconds) {
        crosshairSeries.getData().setAll(List.of(
                new XYChart.Data<>(timeSeconds, yAxis.getLowerBound()),
                new XYChart.Data<>(timeSeconds, yAxis.getUpperBound())));
    }
}