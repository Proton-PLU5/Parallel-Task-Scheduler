package se306.scheduler.gui.metrics;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import se306.scheduler.gui.SearchListener;
import se306.scheduler.gui.metrics.MetricsHistory.Frame;

/**
 * Displays search progress and allows the results to be replayed after completion.
 *
 * <p>Improvements record when a new best schedule is found, while frames
 * periodically record the current search statistics. Both use elapsed time so
 * the chart and statistics stay in sync during playback and hovering.
 *
 * <p>This panel manages the layout and statistics, while the chart and playback
 * controls are handled by {@link ConvergenceChartView} and
 * {@link PlaybackController}.
 */
public class MetricsPanel extends BorderPane {

    /**
     * How often the search status is sampled. {@code MainWindow} drives its timer
     * from this.
     */
    public static final Duration SAMPLE_INTERVAL = Duration.seconds(0.1);

    private static final double METER_HEIGHT = 10;

    private final MetricsHistory history;
    private final PlaybackController playbackController;
    private final ConvergenceChartView chartView;

    private boolean complete;

    /**
     * The frame the tiles show when nothing is hovered, and the anchor the chart is
     * drawn to.
     */
    private int displayedIndex;

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

    public MetricsPanel() {
        getStyleClass().add("metrics-panel");
        statusPill.getStyleClass().addAll("metrics-status-pill", "metrics-status-pill-running");
        timelineLabel.getStyleClass().add("metrics-title");

        history = new MetricsHistory(SAMPLE_INTERVAL);
        playbackController = new PlaybackController(timeSlider, playButton, speedButton, history);
        chartView = new ConvergenceChartView(
                history,
                this::currentUpto,
                frame -> showStats(frame, true),
                () -> showStats(history.frame(Math.min(displayedIndex, history.frameCount() - 1)), false));

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

        BorderPane.setMargin(chartView, new Insets(0, 0, 0, 32));

        setTop(topSection);
        setLeft(leftColumn);
        setCenter(chartView);
    }

    private VBox buildMeterSection() {
        Label sectionLabel = new Label("Search space pruned");
        sectionLabel.getStyleClass().add("metrics-section-label");
        meterValue.getStyleClass().add("metrics-meter-value");

        Label explanationLabel = new Label(
                "The percentage of the search space that has been pruned by the algorithm. " +
                        "Higher percentages indicate that the algorithm is effectively eliminating unpromising branches, leading to faster convergence.");
        explanationLabel.setWrapText(true);
        explanationLabel.getStyleClass().add("metrics-meter-explanation");
        // When the row runs out of width, the HBox shrinks its children; pinning the
        // value to its
        // preferred size makes the section label give way instead, so the trailing "%"
        // is never
        // clipped off the readout.
        meterValue.setMinWidth(Region.USE_PREF_SIZE);

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

        return new VBox(8, meterHeader, new StackPane(meterTrack, meterFill), explanationLabel);
    }

    /**
     * Full comma-grouped digits below a million; "12.3 M" / "4.6 B" / "1.2 T"
     * above, so the huge
     * branch counts a long run produces stay readable in the tiles, the meter, and
     * the chart axis.
     */
    static String formatCount(long value) {
        if (value < 1_000_000L) {
            return String.format("%,d", value);
        }
        if (value < 1_000_000_000L) {
            return String.format("%.1f M", value / 1_000_000.0);
        }
        if (value < 1_000_000_000_000L) {
            return String.format("%.1f B", value / 1_000_000_000.0);
        }
        return String.format("%.1f T", value / 1_000_000_000_000.0);
    }

    private VBox statTile(String labelText, Label valueLabel) {
        Label label = new Label(labelText);
        label.getStyleClass().add("metrics-tile-label");
        valueLabel.getStyleClass().add("metrics-tile-value");
        return new VBox(4, label, valueLabel);
    }

    /**
     * Clears any previous run's history so the panel can be reused for a fresh
     * search.
     */
    public void beginRun() {
        playbackController.reset();
        history.reset(SAMPLE_INTERVAL);
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
        chartView.reset();
    }

    /** Records a new best schedule. Must be called on the FX thread. */
    public void recordImprovement(double elapsedSeconds, int makespan) {
        if (history.recordImprovement(elapsedSeconds, makespan)) {
            refreshLive();
        }
    }

    /**
     * Records one sampled reading of the search status. Must be called on the FX
     * thread.
     */
    public void recordFrame(double elapsedSeconds, SearchMetrics.Snapshot snapshot) {
        if (history.recordFrame(elapsedSeconds, snapshot)) {
            // Every index just moved underneath the cursor and the slider, so drop the
            // hover
            // rather than let it keep reporting a different moment than the one being
            // pointed at.
            chartView.clearHover();
        }
        refreshLive();
    }

    /** Marks the search as complete and enables replay. */
    public void markComplete() {
        complete = true;
        statusPill.setText("✓ Complete");
        statusPill.getStyleClass().remove("metrics-status-pill-running");
        statusPill.getStyleClass().add("metrics-status-pill-complete");

        if (history.isEmpty()) {
            return;
        }
        boolean scrubbable = history.frameCount() > 1;
        timeSlider.setMax(history.frameCount() - 1);
        timeSlider.setDisable(!scrubbable);
        playbackController.setScrubbable(scrubbable);
        timeSlider.setValue(history.frameCount() - 1);
        displayFrame(history.frameCount() - 1);
    }

    /**
     * While the search runs the view pins itself to the newest frame and the slider
     * stays locked.
     */
    private void refreshLive() {
        if (complete) {
            return;
        }
        if (history.isEmpty()) {
            // Show improvements even before the first sample.
            chartView.render(history.lastImprovementTime());
            return;
        }
        int last = history.frameCount() - 1;
        timeSlider.setMax(last);
        if ((int) Math.round(timeSlider.getValue()) == last) {
            // Already pinned to the newest frame, so setValue would be a no-op and the
            // listener
            // would not fire - which is the case when an improvement arrives between
            // samples.
            displayFrame(last);
        } else {
            timeSlider.setValue(last);
        }
    }

    private void displayFrame(int index) {
        if (history.isEmpty()) {
            return;
        }
        displayedIndex = Math.max(0, Math.min(index, history.frameCount() - 1));
        Frame frame = history.frame(displayedIndex);

        // While live, an improvement newer than the last sample should show up
        // immediately rather
        // than waiting for the next tick. During replay the frame's own time is the
        // whole truth.
        double upto = complete ? frame.timeSeconds() : Math.max(frame.timeSeconds(), history.lastImprovementTime());

        chartView.render(upto);

        // A live refresh must not yank the tiles out from under a cursor parked on an
        // earlier
        // moment: while hovering, the hovered frame keeps ownership of the readout.
        chartView.hoveredFrame().ifPresentOrElse(
                hovered -> showStats(hovered, true),
                () -> showStats(frame, false));
    }

    /** Updates the statistics and timeline from a frame. */
    private void showStats(Frame frame, boolean hovering) {
        if (hovering) {
            timelineLabel.setText(String.format("Hover · %.1f s", frame.timeSeconds()));
        } else if (complete) {
            timelineLabel.setText(String.format("Replay · %.1f s of %.1f s",
                    frame.timeSeconds(), history.frame(history.frameCount() - 1).timeSeconds()));
        } else {
            timelineLabel.setText(String.format("Live · %.1f s",
                    Math.max(frame.timeSeconds(), history.lastImprovementTime())));
        }

        branchesValue.setText(formatCount(frame.branchesExplored()));
        bestValue.setText(frame.bestMakespan() == Integer.MAX_VALUE ? "-" : String.format("%,d", frame.bestMakespan()));
        timeValue.setText(String.format("%.1f s", frame.timeSeconds()));
        memoryValue.setText(String.format("%,d MB", frame.usedMemoryBytes() / (1024 * 1024)));
        cpuValue.setText(String.format("%.1f%%", frame.cpuPercent()));

        long pruned = frame.branchesPruned();
        long explored = frame.branchesExplored();
        long generated = pruned + explored;
        double fraction = generated == 0 ? 0 : pruned / (double) generated;
        meterFraction.set(fraction);
        meterValue.setText(String.format("%s of %s · %.1f%%",
                formatCount(pruned), formatCount(generated), fraction * 100));
    }

    /**
     * The latest time currently drawn, which is what the hover is allowed to range
     * over.
     */
    private double currentUpto() {
        if (history.isEmpty()) {
            return history.lastImprovementTime();
        }
        Frame frame = history.frame(Math.min(displayedIndex, history.frameCount() - 1));
        return complete ? frame.timeSeconds() : Math.max(frame.timeSeconds(), history.lastImprovementTime());
    }
}
