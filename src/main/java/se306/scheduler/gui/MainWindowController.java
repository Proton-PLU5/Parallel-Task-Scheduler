package se306.scheduler.gui;

import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

public class MainWindowController {

    private static final double MIN_SCALE = 1.0;
    private static final double MAX_SCALE = 4.0;
    private static final double SCROLL_ZOOM_SENSITIVITY = 0.0015;

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
                clampPan();
            }
        });

        // Scroll to zoom
        chartContainer.addEventFilter(ScrollEvent.SCROLL, event -> {
            double zoomFactor = Math.exp(event.getDeltaY() * SCROLL_ZOOM_SENSITIVITY);
            applyZoom(zoomFactor, event.getSceneX(), event.getSceneY());
            event.consume();
        });

        // Pinch to zoom
        chartContainer.addEventFilter(ZoomEvent.ZOOM, event -> {
            applyZoom(event.getZoomFactor(), event.getSceneX(), event.getSceneY());
            event.consume();
        });
    }

    /**
     * Scales {@code currentPanel} by {@code zoomFactor}, keeping the point under the cursor
     * ({@code sceneX}, {@code sceneY}) visually fixed, and clamps the result between
     * {@link #MIN_SCALE} and {@link #MAX_SCALE}.
     */
    private void applyZoom(double zoomFactor, double sceneX, double sceneY) {
        double oldScale = currentPanel.getScaleX();

        double newScale = Math.max(
            MIN_SCALE,
            Math.min(MAX_SCALE, oldScale * zoomFactor)
        );

        if (newScale == oldScale) {
            return;
        }

        // Find exactly where the mouse is relative to the panel
        javafx.geometry.Point2D mouseInLocal =
            currentPanel.sceneToLocal(sceneX, sceneY);

        // Apply the scale
        currentPanel.setScaleX(newScale);
        currentPanel.setScaleY(newScale);

        // Find where that same local point is now appearing on screen
        javafx.geometry.Point2D mouseAfterScale =
            currentPanel.localToScene(mouseInLocal);

        // Move the panel so the point returns to the mouse position
        currentPanel.setTranslateX(
            currentPanel.getTranslateX()
                + (sceneX - mouseAfterScale.getX())
        );

        currentPanel.setTranslateY(
            currentPanel.getTranslateY()
                + (sceneY - mouseAfterScale.getY())
        );

        clampPan();
    }

    private void clampPan() {
        double containerWidth = chartContainer.getWidth();
        double containerHeight = chartContainer.getHeight();

        Bounds bounds = currentPanel.getBoundsInParent();

        // Horizontal
        if (bounds.getWidth() > containerWidth) {

            // Left edge cannot move past the left side
            if (bounds.getMinX() > 0) {
                currentPanel.setTranslateX(
                    currentPanel.getTranslateX() - bounds.getMinX()
                );
            }

            // Right edge cannot move before the right side
            else if (bounds.getMaxX() < containerWidth) {
                currentPanel.setTranslateX(
                    currentPanel.getTranslateX()
                        + (containerWidth - bounds.getMaxX())
                );
            }

        } else {
            // Content is smaller than viewport:
            // keep it at the top-left
            currentPanel.setTranslateX(
                currentPanel.getTranslateX() - bounds.getMinX()
            );
        }

        // Recalculate because X/Y transforms are independent,
        // but this also gives us the current transformed bounds.
        bounds = currentPanel.getBoundsInParent();

        // Vertical
        if (bounds.getHeight() > containerHeight) {

            // Top edge cannot move below the top
            if (bounds.getMinY() > 0) {
                currentPanel.setTranslateY(
                    currentPanel.getTranslateY() - bounds.getMinY()
                );
            }

            // Bottom edge cannot move above the bottom
            else if (bounds.getMaxY() < containerHeight) {
                currentPanel.setTranslateY(
                    currentPanel.getTranslateY()
                        + (containerHeight - bounds.getMaxY())
                );
            }

        } else {
            // Content is smaller than viewport:
            // keep it at the top
            currentPanel.setTranslateY(
                currentPanel.getTranslateY() - bounds.getMinY()
            );
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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