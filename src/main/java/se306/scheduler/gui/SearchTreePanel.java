package se306.scheduler.gui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class SearchTreePanel extends StackPane {

    public SearchTreePanel() {
        VBox content = new VBox(20);
        content.setAlignment(Pos.CENTER);

        Label title = new Label("(Insert Search Tree Here)");
        title.setStyle("-fx-font-size: 32px; -fx-text-fill: white;");

        content.getChildren().addAll(title);
        getChildren().add(content);
    }
}