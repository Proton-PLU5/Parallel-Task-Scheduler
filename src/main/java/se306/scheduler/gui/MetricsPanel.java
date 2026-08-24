package se306.scheduler.gui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import se306.scheduler.algorithm.SearchContext.Improvement;

import java.util.List;

public class MetricsPanel extends BorderPane {

    private static final double METER_HEIGHT = 10;

    private final Label statusPill = new Label("● Running");
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

    public MetricsPanel() {
        getStyleClass().add("metrics-panel");

        Label title = new Label("Search Metrics");
        title.getStyleClass().add("metrics-title");
        statusPill.getStyleClass().addAll("metrics-status-pill", "metrics-status-pill-running");

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox topRow = new HBox(title, titleSpacer, statusPill);
        topRow.setAlignment(Pos.CENTER_LEFT);
        BorderPane.setMargin(topRow, new Insets(0, 0, 20, 0));

        GridPane tileGrid = new GridPane();
        tileGrid.setHgap(32);
        tileGrid.setVgap(16);
        tileGrid.add(statTile("Total branches", branchesValue), 0, 0);
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

        setTop(topRow);
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

    public void markComplete() {
        statusPill.setText("✓ Complete");
        statusPill.getStyleClass().remove("metrics-status-pill-running");
        statusPill.getStyleClass().add("metrics-status-pill-complete");
    }

    public void update(
            long branchesExplored,
            long branchesPruned,
            int bestMakespan,
            long elapsedMillis,
            long usedMemoryBytes,
            double cpuLoadPercent,
            List<Improvement> history) {
        branchesValue.setText(String.format("%,d", branchesExplored));
        bestValue.setText(bestMakespan == Integer.MAX_VALUE ? "-" : String.format("%,d", bestMakespan));
        timeValue.setText(String.format("%.1f s", elapsedMillis / 1000.0));
        memoryValue.setText(String.format("%,d MB", usedMemoryBytes / (1024 * 1024)));
        cpuValue.setText(String.format("%.1f%%", Math.max(0, cpuLoadPercent)));

        double fraction = branchesExplored == 0 ? 0 : branchesPruned / (double) branchesExplored;
        meterFraction.set(fraction);
        meterValue.setText(String.format(
                "%,d of %,d · %.1f%%", branchesPruned, branchesExplored, fraction * 100));

        updateConvergenceChart(history);
    }

    /**
     * Appends only the points not already plotted, rather than clearing and
     * rebuilding
     * everything each poll, since most polls have nothing new to add.
     */
    private void updateConvergenceChart(List<Improvement> history) {
        int alreadyPlotted = convergenceSeries.getData().size();
        for (int i = alreadyPlotted; i < history.size(); i++) {
            Improvement improvement = history.get(i);
            convergenceSeries.getData().add(
                    new XYChart.Data<>(improvement.elapsedMillis() / 1000.0, improvement.makespan()));
        }

        if (!history.isEmpty()) {
            // Each entry is only recorded when it improves on the previous best, so the
            // history
            // is always strictly decreasing: the first entry is the highest makespan seen,
            // the
            // last is the current best.
            int maxMakespan = history.get(0).makespan();
            int minMakespan = history.get(history.size() - 1).makespan();

            convergenceYAxis.setLowerBound(Math.max(0, minMakespan - 6));
            convergenceYAxis.setUpperBound(maxMakespan + 6);
            convergenceYAxis.setTickUnit(
                    Math.max(1, (convergenceYAxis.getUpperBound() - convergenceYAxis.getLowerBound()) / 5.0));
        }
    }
}
