package se306.scheduler.gui;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

public class MainWindowController {

    @FXML private StackPane chartContainer;

    private GanttChartPanel ganttChart;
    private SearchTreePanel searchTree;
    private StackPane currentPanel;

    @FXML
    public void initialize() {
        ganttChart = new GanttChartPanel(0, 0);
        searchTree = new SearchTreePanel();

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(chartContainer.widthProperty());
        clip.heightProperty().bind(chartContainer.heightProperty());
        chartContainer.setClip(clip);

        // Show gantt chart on main screen by default
        currentPanel = ganttChart;
        chartContainer.getChildren().add(currentPanel);
        StackPane.setAlignment(currentPanel, Pos.TOP_LEFT);
        chartContainer.setPickOnBounds(true);

        // Mouse pressing logic
        final double[] lastDragPosition = new double[2];
        chartContainer.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (!event.isSynthesized() && event.getButton() == MouseButton.PRIMARY) {
                lastDragPosition[0] = event.getSceneX();
                lastDragPosition[1] = event.getSceneY();
            }
        });

        // Mouse dragging logic
        chartContainer.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!event.isSynthesized() && event.isPrimaryButtonDown()) {
                currentPanel.setTranslateX(
                    currentPanel.getTranslateX()
                        + event.getSceneX() - lastDragPosition[0]
                );

                currentPanel.setTranslateY(
                    currentPanel.getTranslateY()
                        + event.getSceneY() - lastDragPosition[1]
                );

                lastDragPosition[0] = event.getSceneX();
                lastDragPosition[1] = event.getSceneY();
            }
        });

        // TODO: Add zooming
    }

    @FXML
    private void showGanttChart() {
        currentPanel = ganttChart;
        chartContainer.getChildren().setAll(currentPanel);
        StackPane.setAlignment(currentPanel, Pos.TOP_LEFT);
    }

    @FXML
    private void showSearchTree() {
        currentPanel = searchTree;
        chartContainer.getChildren().setAll(currentPanel);
        StackPane.setAlignment(currentPanel, Pos.TOP_LEFT);
    }

    public GanttChartPanel getGanttChart() {
        return ganttChart;
    }

    public SearchTreePanel getSearchTree() {
        return searchTree;
    }
}