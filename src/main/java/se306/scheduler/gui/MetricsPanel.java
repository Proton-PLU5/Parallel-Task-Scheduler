package se306.scheduler.gui;

import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import se306.scheduler.algorithm.SearchContext.Improvement;

import java.util.List;

public class MetricsPanel extends BorderPane {

    private static final double CHART_WIDTH = 700;
    private static final double CHART_HEIGHT = 340;

    private final Label statusLabel = new Label("Status: Running...");
    private final Label branchesLabel = new Label("Total branches: 0");
    private final Label prunedLabel = new Label("Branches pruned: 0");
    private final Label bestLabel = new Label("Current best (makespan): -");
    private final Label timeLabel = new Label("Time taken: 0.0 s");
    private final Label memoryLabel = new Label("Memory usage: 0 MB");
    private final Label cpuLabel = new Label("CPU usage: 0%");

    private final NumberAxis convergenceXAxis = new NumberAxis();
    private final NumberAxis convergenceYAxis = new NumberAxis();
    private final LineChart<Number, Number> convergenceChart = new LineChart<>(convergenceXAxis, convergenceYAxis);
    private final XYChart.Series<Number, Number> convergenceSeries = new XYChart.Series<>();

    public MetricsPanel() {
        VBox statsBox = new VBox(10, statusLabel, branchesLabel, prunedLabel, bestLabel, timeLabel, memoryLabel,
                cpuLabel);

        convergenceXAxis.setLabel("Time (s)");
        convergenceYAxis.setLabel("Makespan");
        convergenceYAxis.setAutoRanging(false);
        convergenceChart.setTitle("Convergence");
        convergenceChart.setAnimated(false);
        convergenceChart.setLegendVisible(false);
        convergenceChart.getData().add(convergenceSeries);
        convergenceChart.setPrefSize(CHART_WIDTH, CHART_HEIGHT);
        convergenceChart.setMaxSize(CHART_WIDTH, CHART_HEIGHT);

        setLeft(statsBox);
        setRight(convergenceChart);
        BorderPane.setAlignment(convergenceChart, Pos.TOP_CENTER);
    }

    public void markComplete() {
        statusLabel.setText("Status: Complete");
    }

    public void update(
            long branchesExplored,
            long branchesPruned,
            int bestMakespan,
            long elapsedMillis,
            long usedMemoryBytes,
            double cpuLoadPercent,
            List<Improvement> history) {
        branchesLabel.setText("Total branches: " + branchesExplored);
        prunedLabel.setText("Branches pruned: " + branchesPruned);
        bestLabel.setText("Current best (makespan): " + (bestMakespan == Integer.MAX_VALUE ? "-" : bestMakespan));
        timeLabel.setText(String.format("Time taken: %.1f s", elapsedMillis / 1000.0));
        memoryLabel.setText(String.format("Memory usage: %d MB", usedMemoryBytes / (1024 * 1024)));
        cpuLabel.setText(String.format("CPU usage: %.1f%%", Math.max(0, cpuLoadPercent)));
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