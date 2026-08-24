package se306.scheduler.gui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
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
    private MetricsPanel metricsPanel;
    private Pane currentPanel;
    private StackPane currentPanel;
    private boolean autoFitEnabled = true;
    private double lastDragSceneX;
    private double lastDragSceneY;

    @FXML
    public void initialize() {
        ganttChart = new GanttChartPanel(0, 0);
        searchTree = new SearchTreePanel();
        metricsPanel = new MetricsPanel();

        // Keep visualization panel sizes from participating in parent layout.
        // This prevents very large graphs from expanding the window/viewport size.
        ganttChart.setManaged(false);
        searchTree.setManaged(false);

        configureViewportClip();
        configureDefaultPanel();
        configureAutoFitListeners();
        configureInputHandlers();
        scheduleEnforceMinScaleAndClamp();
    }

    private void configureViewportClip() {
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(chartContainer.widthProperty());
        clip.heightProperty().bind(chartContainer.heightProperty());
        chartContainer.setClip(clip);
    }

    private void configureDefaultPanel() {
        currentPanel = ganttChart;
        chartContainer.getChildren().add(currentPanel);
        StackPane.setAlignment(currentPanel, Pos.CENTER);
        chartContainer.setPickOnBounds(true);
    }

    private void configureAutoFitListeners() {
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
    }

    private void configureInputHandlers() {
        chartContainer.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (!event.isSynthesized() && event.getButton() == MouseButton.PRIMARY) {
                lastDragSceneX = event.getSceneX();
                lastDragSceneY = event.getSceneY();
            }
        });

        chartContainer.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!event.isSynthesized() && event.isPrimaryButtonDown()) {
                double deltaX = event.getSceneX() - lastDragSceneX;
                double deltaY = event.getSceneY() - lastDragSceneY;

                applyTranslationDelta(deltaX, deltaY);

                lastDragSceneX = event.getSceneX();
                lastDragSceneY = event.getSceneY();
                clampPan();
            }
        });

        chartContainer.addEventFilter(ScrollEvent.SCROLL, event -> {
            double zoomFactor = Math.exp(event.getDeltaY() * SCROLL_ZOOM_SENSITIVITY);
            applyZoom(zoomFactor, event.getSceneX(), event.getSceneY());
            event.consume();
        });

        chartContainer.addEventFilter(ZoomEvent.ZOOM, event -> {
            applyZoom(event.getZoomFactor(), event.getSceneX(), event.getSceneY());
            event.consume();
        });
    }

    private void scheduleEnforceMinScaleAndClamp() {
        Platform.runLater(this::enforceMinScaleAndClamp);
    }

    /**
     * Handles zoom math and cursor anchoring.
     * Scales currentPanel by zoomFactor, keeping the point under the cursor
     * (sceneX, sceneY) visually fixed, and clamps the result between
     * the dynamic per-panel minimum and MAX_SCALE.
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

        // Disable auto-fit once the user performs a real zoom-in beyond fit scale.
        if (newScale > minScale + SCALE_EPSILON) {
            autoFitEnabled = false;
        }

        javafx.geometry.Point2D mouseInLocal =
            currentPanel.sceneToLocal(sceneX, sceneY);

        setCurrentPanelScale(newScale);

        javafx.geometry.Point2D mouseAfterScale =
            currentPanel.localToScene(mouseInLocal);

        applyTranslationDelta(
            sceneX - mouseAfterScale.getX(),
            sceneY - mouseAfterScale.getY()
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

    /**
     * Handles auto fitting.
     */
    private void enforceMinScaleAndClamp() {
        if (currentPanel == null) {
            return;
        }

        double minScale = computeMinScaleForCurrentPanel();
        boolean shouldApplyScale = autoFitEnabled || currentPanel.getScaleX() < minScale;
        if (shouldApplyScale) {
            setCurrentPanelScale(minScale);
        }

        if (autoFitEnabled) {
            centerCurrentPanel();
            return;
        }

        clampPan();
    }

    private void setCurrentPanelScale(double scale) {
        currentPanel.setScaleX(scale);
        currentPanel.setScaleY(scale);
    }

    private void applyTranslationDelta(double deltaX, double deltaY) {
        currentPanel.setTranslateX(currentPanel.getTranslateX() + deltaX);
        currentPanel.setTranslateY(currentPanel.getTranslateY() + deltaY);
    }

    private void centerCurrentPanel() {
        double containerWidth = chartContainer.getWidth();
        double containerHeight = chartContainer.getHeight();
        Bounds bounds = currentPanel.getBoundsInParent();

        double centeredMinX = (containerWidth - bounds.getWidth()) / 2.0;
        double centeredMinY = (containerHeight - bounds.getHeight()) / 2.0;

        applyTranslationDelta(
            centeredMinX - bounds.getMinX(),
            centeredMinY - bounds.getMinY()
        );
    }

    /**
     * Handles boundary constraints.
     */
    private void clampPan() {
        double containerWidth = chartContainer.getWidth();
        double containerHeight = chartContainer.getHeight();

        Bounds bounds = currentPanel.getBoundsInParent();

        // Horizontal
        if (bounds.getWidth() > containerWidth + SCALE_EPSILON) {
            if (bounds.getMinX() > 0) {
                applyTranslationDelta(-bounds.getMinX(), 0);
            } else if (bounds.getMaxX() < containerWidth) {
                applyTranslationDelta(containerWidth - bounds.getMaxX(), 0);
            }
        } else {
            double centeredMinX = (containerWidth - bounds.getWidth()) / 2.0;
            applyTranslationDelta(centeredMinX - bounds.getMinX(), 0);
        }

        bounds = currentPanel.getBoundsInParent();

        // Vertical
        if (bounds.getHeight() > containerHeight + SCALE_EPSILON) {
            if (bounds.getMinY() > 0) {
                applyTranslationDelta(0, -bounds.getMinY());
            } else if (bounds.getMaxY() < containerHeight) {
                applyTranslationDelta(0, containerHeight - bounds.getMaxY());
            }
        } else {
            double centeredMinY = (containerHeight - bounds.getHeight()) / 2.0;
            applyTranslationDelta(0, centeredMinY - bounds.getMinY());
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

        autoFitEnabled = true;

        currentPanel.setTranslateX(0);
        currentPanel.setTranslateY(0);
        enforceMinScaleAndClamp();
        scheduleEnforceMinScaleAndClamp();
    }

    @FXML
    private void showMetrics() {
        currentPanel = metricsPanel;
        chartContainer.getChildren().setAll(currentPanel);
        StackPane.setAlignment(currentPanel, Pos.TOP_LEFT);
    }

    public GanttChartPanel getGanttChart() {
        return ganttChart;
    }

    public SearchTreePanel getSearchTree() {
        return searchTree;
    }

    public MetricsPanel getMetricsPanel() {
        return metricsPanel;
    }
}