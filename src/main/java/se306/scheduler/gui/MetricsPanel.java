package se306.scheduler.gui;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class MetricsPanel extends VBox {

    private final Label nodesLabel = new Label("Nodes explored: 0");
    private final Label prunedLabel = new Label("Nodes pruned: 0");
    private final Label bestLabel = new Label("Best makespan: —");

    public MetricsPanel() {
        setSpacing(10);
        getChildren().addAll(nodesLabel, prunedLabel, bestLabel);
    }

    public void update(long nodesExplored, long nodesPruned, int bestMakespan) {
        nodesLabel.setText("Nodes explored: " + nodesExplored);
        prunedLabel.setText("Nodes pruned: " + nodesPruned);
        bestLabel.setText("Best makespan: " + bestMakespan);
    }
}