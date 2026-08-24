package se306.scheduler.gui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
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

import se306.scheduler.algorithm.SearchContext.Checkpoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MetricsPanel extends BorderPane {

    private static final double METER_HEIGHT = 10;
    private static final Duration PLAYBACK_FRAME = Duration.millis(150);
    private static final int MAX_REPLAY_CHECKPOINTS = 50;

    private final Label statusPill = new Label("● Running");
    private final Label checkpointLabel = new Label("Checkpoint 0 of 0");
    private final Button playButton = new Button("Play");
    private final Slider checkpointSlider = new Slider(0, 0, 0);

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
    private final XYChart.Series<Number, Number> convergenceSeries = new XYChart.Series<>();
    private final Label noConvergenceDataLabel =
            new Label("Not enough recorded steps to show a convergence trend.");

    private List<Checkpoint> checkpoints = List.of();
    private Timeline playbackTimeline;

    public MetricsPanel() {
        getStyleClass().add("metrics-panel");
        statusPill.getStyleClass().addAll("metrics-status-pill", "metrics-status-pill-running");
        checkpointLabel.getStyleClass().add("metrics-title");
        playButton.setDisable(true);
        playButton.setOnAction(e -> togglePlayback());

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(12, checkpointLabel, headerSpacer, statusPill, playButton);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        checkpointSlider.setDisable(true);
        checkpointSlider.setSnapToTicks(true);
        checkpointSlider.setMajorTickUnit(1);
        checkpointSlider.setMaxWidth(Double.MAX_VALUE);
        checkpointSlider.valueProperty().addListener(
                (obs, oldVal, newVal) -> displayCheckpoint((int) Math.round(newVal.doubleValue())));

        VBox topSection = new VBox(12, headerRow, checkpointSlider);
        BorderPane.setMargin(topSection, new Insets(0, 0, 20, 0));

        GridPane tileGrid = new GridPane();
        tileGrid.setHgap(32);
        tileGrid.setVgap(16);
        tileGrid.add(statTile("Branches explored", branchesValue), 0, 0);
        tileGrid.add(statTile("Current best (makespan)", bestValue), 1, 0);
        tileGrid.add(statTile("Time taken", timeValue), 0, 1);
        tileGrid.add(statTile("Memory usage", memoryValue), 1, 1);
        tileGrid.add(statTile("CPU usage", cpuValue), 0, 2);

        VBox meterSection = buildMeterSection();

        VBox leftColumn = new VBox(28, tileGrid, meterSection);
        leftColumn.setPrefWidth(300);

        convergenceXAxis.setLabel("Time (s)");
        convergenceYAxis.setLabel("Makespan");
        convergenceYAxis.setAutoRanging(false);
        convergenceChart.setTitle("Convergence");
        convergenceChart.setAnimated(false);
        convergenceChart.setLegendVisible(false);
        convergenceChart.getData().add(convergenceSeries);
        convergenceChart.getStyleClass().add("metrics-chart");
        BorderPane.setMargin(convergenceChart, new Insets(0, 0, 0, 32));

        noConvergenceDataLabel.getStyleClass().add("metrics-section-label");
        noConvergenceDataLabel.setAlignment(Pos.CENTER);
        noConvergenceDataLabel.setMaxWidth(Double.MAX_VALUE);
        noConvergenceDataLabel.setMaxHeight(Double.MAX_VALUE);
        BorderPane.setMargin(noConvergenceDataLabel, new Insets(0, 0, 0, 32));

        setTop(topSection);
        setLeft(leftColumn);
        setCenter(convergenceChart);
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

        StackPane meterBar = new StackPane(meterTrack, meterFill);

        return new VBox(8, meterHeader, meterBar);
    }

    private VBox statTile(String labelText, Label valueLabel) {
        Label label = new Label(labelText);
        label.getStyleClass().add("metrics-tile-label");
        valueLabel.getStyleClass().add("metrics-tile-value");
        return new VBox(4, label, valueLabel);
    }

    /** Called while the search is still running: follows the latest checkpoint, slider stays locked. */
    public void updateLive(List<Checkpoint> latestCheckpoints) {
        // Downsample here too, not just at completion: the raw log is recorded every 100
        // branches and can reach hundreds of thousands of entries mid-run, and re-plotting all
        // of them on every 200ms poll tick is what was making the panel unusably slow.
        checkpoints = downsample(latestCheckpoints);
        if (checkpoints.isEmpty()) {
            return;
        }
        checkpointSlider.setMax(checkpoints.size() - 1);
        checkpointSlider.setValue(checkpoints.size() - 1);
        displayCheckpoint(checkpoints.size() - 1);
    }

    /** Called once the search finishes: unlocks the slider and Play button for scrubbable replay. */
    public void markComplete(List<Checkpoint> finalCheckpoints) {
        checkpoints = downsample(finalCheckpoints);
        statusPill.setText("✓ Complete");
        statusPill.getStyleClass().remove("metrics-status-pill-running");
        statusPill.getStyleClass().add("metrics-status-pill-complete");

        if (checkpoints.isEmpty()) {
            return;
        }
        checkpointSlider.setMax(checkpoints.size() - 1);
        checkpointSlider.setDisable(checkpoints.size() <= 1);
        playButton.setDisable(checkpoints.size() <= 1);
        checkpointSlider.setValue(checkpoints.size() - 1);
        displayCheckpoint(checkpoints.size() - 1);
    }

    /**
     * Picks up to MAX_REPLAY_CHECKPOINTS points from the full checkpoint log, prioritising every
     * point where the makespan actually changed (the real "steps" of the staircase). Sampling
     * evenly by position alone tends to bury those steps inside the much longer flat
     * "proving optimality" tail that typically follows the early improvements. Any leftover
     * budget is filled with evenly-spaced points from the full log, so the flat stretches stay
     * represented too.
     */
    private static List<Checkpoint> downsample(List<Checkpoint> full) {
        if (full.size() <= MAX_REPLAY_CHECKPOINTS) {
            return full;
        }

        List<Checkpoint> changePoints = new ArrayList<>();
        int lastMakespan = Integer.MIN_VALUE;
        for (Checkpoint checkpoint : full) {
            if (checkpoint.bestMakespan() != lastMakespan) {
                changePoints.add(checkpoint);
                lastMakespan = checkpoint.bestMakespan();
            }
        }
        Checkpoint lastEntry = full.get(full.size() - 1);
        if (changePoints.isEmpty() || !changePoints.get(changePoints.size() - 1).equals(lastEntry)) {
            changePoints.add(lastEntry);
        }

        if (changePoints.size() >= MAX_REPLAY_CHECKPOINTS) {
            return evenlySample(changePoints, MAX_REPLAY_CHECKPOINTS);
        }

        List<Checkpoint> filler = evenlySample(full, MAX_REPLAY_CHECKPOINTS - changePoints.size());
        List<Checkpoint> merged = new ArrayList<>(changePoints);
        for (Checkpoint checkpoint : filler) {
            if (!merged.contains(checkpoint)) {
                merged.add(checkpoint);
            }
        }
        merged.sort(Comparator.comparingLong(Checkpoint::branchesExplored));
        return merged;
    }

    /** Evenly picks up to `count` points from `source`, always keeping the last. */
    private static List<Checkpoint> evenlySample(List<Checkpoint> source, int count) {
        if (count <= 0) {
            return List.of();
        }
        if (source.size() <= count) {
            return source;
        }
        if (count == 1) {
            return List.of(source.get(source.size() - 1));
        }

        List<Checkpoint> sampled = new ArrayList<>(count);
        double step = (source.size() - 1) / (double) (count - 1);
        for (int i = 0; i < count; i++) {
            int index = Math.min((int) Math.round(i * step), source.size() - 1);
            sampled.add(source.get(index));
        }
        return sampled;
    }

    private void togglePlayback() {
        if (playbackTimeline != null && playbackTimeline.getStatus() == Animation.Status.RUNNING) {
            playbackTimeline.pause();
            playButton.setText("Play");
            return;
        }

        if ((int) checkpointSlider.getValue() >= checkpoints.size() - 1) {
            checkpointSlider.setValue(0);
        }

        playbackTimeline = new Timeline(new KeyFrame(PLAYBACK_FRAME, event -> {
            int next = (int) checkpointSlider.getValue() + 1;
            if (next >= checkpoints.size()) {
                playbackTimeline.stop();
                playButton.setText("Play");
                return;
            }
            checkpointSlider.setValue(next);
        }));
        playbackTimeline.setCycleCount(Animation.INDEFINITE);
        playbackTimeline.play();
        playButton.setText("Pause");
    }

    private void displayCheckpoint(int index) {
        if (checkpoints.isEmpty()) {
            return;
        }
        index = Math.max(0, Math.min(index, checkpoints.size() - 1));
        Checkpoint checkpoint = checkpoints.get(index);

        checkpointLabel.setText(String.format("Checkpoint %d of %d", index + 1, checkpoints.size()));
        branchesValue.setText(String.format("%,d", checkpoint.branchesExplored()));
        bestValue.setText(
                checkpoint.bestMakespan() == Integer.MAX_VALUE ? "-" : String.format("%,d", checkpoint.bestMakespan()));
        timeValue.setText(String.format("%.1f s", checkpoint.elapsedNanos() / 1_000_000_000.0));
        memoryValue.setText(String.format("%,d MB", checkpoint.usedMemoryBytes() / (1024 * 1024)));
        cpuValue.setText(String.format("%.1f%%", checkpoint.cpuLoadPercent()));

        long pruned = checkpoint.branchesPruned();
        long explored = checkpoint.branchesExplored();
        double fraction = explored == 0 ? 0 : pruned / (double) explored;
        meterFraction.set(fraction);
        meterValue.setText(String.format("%,d of %,d · %.1f%%", pruned, explored, fraction * 100));

        updateConvergenceChart(index);
    }

    /** Shows only checkpoints up to (and including) the one currently selected. */
    private void updateConvergenceChart(int uptoIndexInclusive) {
        List<Checkpoint> visible = checkpoints.subList(0, uptoIndexInclusive + 1);

        List<Checkpoint> withMakespan = new ArrayList<>();
        for (Checkpoint checkpoint : visible) {
            if (checkpoint.bestMakespan() != Integer.MAX_VALUE) {
                withMakespan.add(checkpoint);
            }
        }

        if (withMakespan.size() >= 2) {
            plotMakespanConvergence(withMakespan);
        } else {
            plotSearchProgress(visible);
        }
    }

    /** The normal case: makespan improving over time. */
    private void plotMakespanConvergence(List<Checkpoint> points) {
        convergenceChart.setTitle("Convergence");
        convergenceYAxis.setLabel("Makespan");
        convergenceSeries.getData().clear();

        int maxMakespan = Integer.MIN_VALUE;
        int minMakespan = Integer.MAX_VALUE;
        for (Checkpoint checkpoint : points) {
            convergenceSeries.getData().add(
                    new XYChart.Data<>(checkpoint.elapsedNanos() / 1_000_000_000.0, checkpoint.bestMakespan()));
            maxMakespan = Math.max(maxMakespan, checkpoint.bestMakespan());
            minMakespan = Math.min(minMakespan, checkpoint.bestMakespan());
        }
        convergenceYAxis.setLowerBound(Math.max(0, minMakespan - 6));
        convergenceYAxis.setUpperBound(maxMakespan + 6);
        convergenceYAxis.setTickUnit(
                Math.max(1, (convergenceYAxis.getUpperBound() - convergenceYAxis.getLowerBound()) / 5.0));
        setCenter(convergenceChart);
    }

    /**
     * Fallback for a search with too few (or zero) makespan improvements to show a convergence
     * trend - e.g. a 1-processor search has no branching, so it only ever finds one complete
     * schedule, all at once. Plots branches explored over time instead, which always has a real
     * multi-point story to tell (the two-tier checkpoint interval in SearchContext guarantees a
     * small search still gets plenty of checkpoints to plot here).
     */
    private void plotSearchProgress(List<Checkpoint> points) {
        if (points.size() < 2) {
            setCenter(noConvergenceDataLabel);
            return;
        }

        convergenceChart.setTitle("Search Progress");
        convergenceYAxis.setLabel("Branches explored");
        convergenceSeries.getData().clear();

        long maxBranches = 0;
        for (Checkpoint checkpoint : points) {
            convergenceSeries.getData().add(
                    new XYChart.Data<>(checkpoint.elapsedNanos() / 1_000_000_000.0, checkpoint.branchesExplored()));
            maxBranches = Math.max(maxBranches, checkpoint.branchesExplored());
        }
        convergenceYAxis.setLowerBound(0);
        convergenceYAxis.setUpperBound(maxBranches + Math.max(1, maxBranches / 10));
        convergenceYAxis.setTickUnit(Math.max(1, convergenceYAxis.getUpperBound() / 5.0));
        setCenter(convergenceChart);
    }
}
