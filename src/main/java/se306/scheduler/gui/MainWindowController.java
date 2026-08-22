package se306.scheduler.gui;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

public class MainWindowController {

    @FXML private StackPane chartContainer;

    private GanttChartPanel ganttChart;

    @FXML
    public void initialize() {
        ganttChart = new GanttChartPanel(0, 0);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(chartContainer.widthProperty());
        clip.heightProperty().bind(chartContainer.heightProperty());
        chartContainer.setClip(clip);

        // Show gantt chart on main screen by default
        chartContainer.getChildren().add(ganttChart);
        StackPane.setAlignment(ganttChart, Pos.TOP_LEFT);
        chartContainer.setPickOnBounds(true);

        // Prevents gantt chart from extending over menu bar
        ganttChart.setManaged(false);

        // Mouse dragging logic
        final double[] lastDragPosition = new double[2];
        chartContainer.setOnMousePressed(event -> {
            if (!event.isSynthesized() && event.getButton() == MouseButton.PRIMARY) {
                lastDragPosition[0] = event.getSceneX();
                lastDragPosition[1] = event.getSceneY();
            }
        });
        chartContainer.setOnMouseDragged(event -> {
            if (!event.isSynthesized() && event.isPrimaryButtonDown()) {
                ganttChart.setTranslateX(ganttChart.getTranslateX()
                    + event.getSceneX() - lastDragPosition[0]);
                ganttChart.setTranslateY(ganttChart.getTranslateY()
                    + event.getSceneY() - lastDragPosition[1]);
                lastDragPosition[0] = event.getSceneX();
                lastDragPosition[1] = event.getSceneY();
            }
        });
    }

    @FXML
    private void showGanttChart() {
        chartContainer.getChildren().setAll(ganttChart);
    }

    @FXML
    private void showSearchTree() {
        // wire up once SearchTreePanel exists
        // chartContainer.getChildren().setAll(searchTree);
    }

    public GanttChartPanel getGanttChart() {
        return ganttChart;
    }
}