package se306.scheduler.gui;

import javafx.application.Platform;
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

    private static final double MIN_BASE_SCALE = 1.0;
    private static final double MAX_SCALE = 4.0;
    private static final double SCROLL_ZOOM_SENSITIVITY = 0.0015;
    private static final double SCALE_EPSILON = 1e-6;

    @FXML private StackPane chartContainer;

    private GanttChartPanel ganttChart;
    private SearchTreePanel searchTree;
    private StackPane currentPanel;
    private boolean autoFitEnabled = true;

    private void scheduleEnforceMinScaleAndClamp() {
        Platform.runLater(this::enforceMinScaleAndClamp);
    }

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
        StackPane.setAlignment(currentPanel, Pos.CENTER);
        chartContainer.setPickOnBounds(true);

        // Keep the view fitted when viewport or gantt size changes.
        chartContainer.widthProperty().addListener((obs, oldVal, newVal) -> scheduleEnforceMinScaleAndClamp());
        chartContainer.heightProperty().addListener((obs, oldVal, newVal) -> scheduleEnforceMinScaleAndClamp());
        ganttChart.prefWidthProperty().addListener((obs, oldVal, newVal) -> {
            if (currentPanel == ganttChart) {
                scheduleEnforceMinScaleAndClamp();
            }
        });
        ganttChart.prefHeightProperty().addListener((obs, oldVal, newVal) -> {
            if (currentPanel == ganttChart) {
                scheduleEnforceMinScaleAndClamp();
            }
        });

        scheduleEnforceMinScaleAndClamp();

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
     * the dynamic per-panel minimum and {@link #MAX_SCALE}.
     */
    private void applyZoom(double zoomFactor, double sceneX, double sceneY) {
        double oldScale = currentPanel.getScaleX();
        double minScale = computeMinScaleForCurrentPanel();

        double newScale = Math.max(
            minScale,
            Math.min(MAX_SCALE, oldScale * zoomFactor)
        );

        if (newScale == oldScale) {
            return;
        }

        // Disable auto-fit once the user performs a real zoom-in beyond the default fit scale.
        if (newScale > minScale + SCALE_EPSILON) {
            autoFitEnabled = false;
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

    private double computeMinScaleForCurrentPanel() {
        if (currentPanel == null) {
            return MIN_BASE_SCALE;
        }

        double contentWidth = currentPanel.prefWidth(-1);
        double contentHeight = currentPanel.prefHeight(-1);

        if (contentWidth <= 0 || contentHeight <= 0) {
            Bounds layoutBounds = currentPanel.getLayoutBounds();
            contentWidth = layoutBounds.getWidth();
            contentHeight = layoutBounds.getHeight();
        }

        double containerWidth = chartContainer.getWidth();
        double containerHeight = chartContainer.getHeight();

        if (contentWidth <= 0 || contentHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
            return MIN_BASE_SCALE;
        }

        double fitScale = Math.min(containerWidth / contentWidth, containerHeight / contentHeight);
        return Math.min(MIN_BASE_SCALE, fitScale);
    }

    private void enforceMinScaleAndClamp() {
        if (currentPanel == null) {
            return;
        }

        double minScale = computeMinScaleForCurrentPanel();
        boolean shouldApplyScale = autoFitEnabled || currentPanel.getScaleX() < minScale;
        if (shouldApplyScale) {
            currentPanel.setScaleX(minScale);
            currentPanel.setScaleY(minScale);
        }

        if (autoFitEnabled) {
            centerCurrentPanel();
            return;
        }

        clampPan();
    }

    private void centerCurrentPanel() {
        double containerWidth = chartContainer.getWidth();
        double containerHeight = chartContainer.getHeight();
        Bounds bounds = currentPanel.getBoundsInParent();

        double centeredMinX = (containerWidth - bounds.getWidth()) / 2.0;
        double centeredMinY = (containerHeight - bounds.getHeight()) / 2.0;

        currentPanel.setTranslateX(
            currentPanel.getTranslateX() + (centeredMinX - bounds.getMinX())
        );

        currentPanel.setTranslateY(
            currentPanel.getTranslateY() + (centeredMinY - bounds.getMinY())
        );
    }

    private void clampPan() {
        double containerWidth = chartContainer.getWidth();
        double containerHeight = chartContainer.getHeight();

        Bounds bounds = currentPanel.getBoundsInParent();

        // Horizontal
        if (bounds.getWidth() > containerWidth + SCALE_EPSILON) {

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
            // Content is smaller than viewport: keep it centered.
            double centeredMinX = (containerWidth - bounds.getWidth()) / 2.0;
            currentPanel.setTranslateX(
                currentPanel.getTranslateX() + (centeredMinX - bounds.getMinX())
            );
        }

        // Recalculate because X/Y transforms are independent,
        // but this also gives us the current transformed bounds.
        bounds = currentPanel.getBoundsInParent();

        // Vertical
        if (bounds.getHeight() > containerHeight + SCALE_EPSILON) {

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
            // Content is smaller than viewport: keep it centered.
            double centeredMinY = (containerHeight - bounds.getHeight()) / 2.0;
            currentPanel.setTranslateY(
                currentPanel.getTranslateY() + (centeredMinY - bounds.getMinY())
            );
        }
    }

    @FXML
    private void showGanttChart() {
        switchToPanel(ganttChart);
    }

    @FXML
    private void showSearchTree() {
        switchToPanel(searchTree);
    }

    private void switchToPanel(StackPane panel) {
        currentPanel = panel;
        chartContainer.getChildren().setAll(currentPanel);
        StackPane.setAlignment(currentPanel, Pos.CENTER);

        // Start each panel in auto-fit mode.
        autoFitEnabled = true;

        // Start each panel at its own fit scale and centered position.
        currentPanel.setTranslateX(0);
        currentPanel.setTranslateY(0);
        enforceMinScaleAndClamp();
        scheduleEnforceMinScaleAndClamp();
    }

    public GanttChartPanel getGanttChart() {
        return ganttChart;
    }

    public SearchTreePanel getSearchTree() {
        return searchTree;
    }
}