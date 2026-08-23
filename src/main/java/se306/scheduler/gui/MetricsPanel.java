package se306.scheduler.gui;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class MetricsPanel extends VBox {

    private final Label statusLabel = new Label("Status: Running...");
    private final Label branchesLabel = new Label("Total branches: 0");
    private final Label prunedLabel = new Label("Branches pruned: 0");
    private final Label bestLabel = new Label("Current best (makespan): -");
    private final Label timeLabel = new Label("Time taken: 0.0 s");
    private final Label memoryLabel = new Label("Memory usage: 0 MB");
    private final Label cpuLabel = new Label("CPU usage: 0%");

    public MetricsPanel() {
        setSpacing(10);
        getChildren().addAll(statusLabel, branchesLabel, prunedLabel, bestLabel, timeLabel, memoryLabel, cpuLabel);
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
            double cpuLoadPercent) {
        branchesLabel.setText("Total branches: " + branchesExplored);
        prunedLabel.setText("Branches pruned: " + branchesPruned);
        bestLabel.setText("Current best (makespan): " + (bestMakespan == Integer.MAX_VALUE ? "-" : bestMakespan));
        timeLabel.setText(String.format("Time taken: %.1f s", elapsedMillis / 1000.0));
        memoryLabel.setText(String.format("Memory usage: %d MB", usedMemoryBytes / (1024 * 1024)));
        cpuLabel.setText(String.format("CPU usage: %.1f%%", Math.max(0, cpuLoadPercent)));
    }
}