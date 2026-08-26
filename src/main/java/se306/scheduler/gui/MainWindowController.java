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
import se306.scheduler.gui.gantt.GanttChartPanel;
import se306.scheduler.gui.metrics.MetricsPanel;

/**
 * Controller for {@code MainWindow.fxml} — owns the visualisation viewport (the {@code chartContainer}
 * region) and everything needed to interact with whatever panel is currently shown in it: panning
 * (mouse drag), zooming (scroll wheel / trackpad scroll / pinch), auto-fit-to-viewport on load, and
 * switching between the Gantt chart and metrics panel.
 *
 * <p>The controller never runs the search itself — it only exposes the panels ({@link #getGanttChart()},
 * {@link #getMetricsPanel()}) so {@link MainWindow} can push updates into them from the algorithm side.
 */
public class MainWindowController {

    /** Never zoom further out than "fit to viewport" would already show — 1.0 = no extra zoom-out beyond that. */
    private static final double MIN_BASE_SCALE = 1.0;
    /** Upper limit on how far the user can zoom in. */
    private static final double MAX_SCALE = 4.0;
    /** Converts raw scroll delta into a multiplicative zoom factor; smaller = gentler zoom per scroll tick. */
    private static final double SCROLL_ZOOM_SENSITIVITY = 0.0015;
    /** Tolerance for floating-point scale/bounds comparisons, to avoid jitter from rounding noise. */
    private static final double SCALE_EPSILON = 1e-6;

    @FXML private StackPane chartContainer;

    private GanttChartPanel ganttChart;
    private MetricsPanel metricsPanel;

    /** Whichever of {@link #ganttChart} / {@link #metricsPanel} is currently displayed in {@link #chartContainer}. */
    private Pane currentPanel;

    /** True while the current panel should stay auto-fitted and centered as it or the viewport resizes;
     *  turned off the moment the user manually zooms in past the fit scale. */
    private boolean autoFitEnabled = true;

    /** Scene coordinates of the last mouse-drag sample, used to compute per-frame drag deltas. */
    private double lastDragSceneX;
    private double lastDragSceneY;

    /**
     * Called automatically by {@link javafx.fxml.FXMLLoader} once the FXML has loaded and this
     * controller's {@code @FXML} fields have been injected. Builds the two content panels, wires up
     * all mouse/scroll/zoom handling on the viewport, and performs the first auto-fit.
     */
    @FXML
    public void initialize() {
        ganttChart = new GanttChartPanel(0, 0);
        metricsPanel = new MetricsPanel();

        // Keep visualization panel sizes from participating in parent layout.
        // This prevents very large graphs from expanding the window/viewport size.
        ganttChart.setManaged(false);

        configureViewportClip();
        configureDefaultPanel();
        configureAutoFitListeners();
        configureInputHandlers();
        scheduleEnforceMinScaleAndClamp();
    }

    /**
     * Clips {@link #chartContainer} to its own bounds, so a panel that grows larger than the
     * viewport (e.g. a Gantt chart with a large makespan) never visually spills outside it —
     * panning/zooming, not overflow, is how the rest of an oversized panel is reached.
     */
    private void configureViewportClip() {
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(chartContainer.widthProperty());
        clip.heightProperty().bind(chartContainer.heightProperty());
        chartContainer.setClip(clip);
    }

    /** Shows the metrics panel first on startup, and enables mouse events over the whole viewport area. */
    private void configureDefaultPanel() {
        currentPanel = metricsPanel;
        chartContainer.getChildren().add(currentPanel);
        StackPane.setAlignment(currentPanel, Pos.CENTER);
        chartContainer.setPickOnBounds(true);
    }

    /**
     * Re-runs auto-fit whenever the viewport resizes, or whenever the Gantt chart's own preferred
     * size changes (e.g. it grows to fit a newly-found, larger schedule) while it's the panel on
     * screen — so the fit scale/centering always matches the current content and container size.
     */
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

    /** Registers drag-to-pan, scroll-to-zoom, and pinch-to-zoom handling on the viewport. */
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

        // Mouse wheel on PC, and two-finger scroll on trackpad.
        chartContainer.addEventFilter(ScrollEvent.SCROLL, event -> {
            double zoomFactor = Math.exp(event.getDeltaY() * SCROLL_ZOOM_SENSITIVITY);
            applyZoom(zoomFactor, event.getSceneX(), event.getSceneY());
            event.consume();
        });

        // Native pinch gesture, where the platform recognises it (e.g. macOS trackpads).
        chartContainer.addEventFilter(ZoomEvent.ZOOM, event -> {
            applyZoom(event.getZoomFactor(), event.getSceneX(), event.getSceneY());
            event.consume();
        });
    }

    /** Defers {@link #enforceMinScaleAndClamp()} to the next pulse, so it runs after the layout pass
     *  that triggered it has actually applied the new sizes (bounds queried too early would be stale). */
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

        // The point under the cursor moved when the scale changed; nudge translation by exactly
        // that offset so it lands back under the cursor, giving the zoom-toward-cursor feel.
        applyTranslationDelta(
            sceneX - mouseAfterScale.getX(),
            sceneY - mouseAfterScale.getY()
        );

        clampPan();
    }

    /**
     * The smallest scale that still fits the current panel's full content inside the viewport
     * without cropping — i.e. "zoom to fit". Falls back to {@link #MIN_BASE_SCALE} when content or
     * container size isn't known yet (e.g. before the first layout pass).
     */
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
     * Re-applies the fit scale (and re-centers) whenever auto-fit is active, or forces the scale
     * back up to the minimum if something (e.g. a viewport resize) has left it too small; otherwise
     * just re-clamps the current pan position against the current bounds.
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

    /** Sets both scale axes together, since panels are always scaled uniformly (no independent X/Y zoom). */
    private void setCurrentPanelScale(double scale) {
        currentPanel.setScaleX(scale);
        currentPanel.setScaleY(scale);
    }

    /** Adds the given offset to the current panel's translation — the one place translation is ever changed. */
    private void applyTranslationDelta(double deltaX, double deltaY) {
        currentPanel.setTranslateX(currentPanel.getTranslateX() + deltaX);
        currentPanel.setTranslateY(currentPanel.getTranslateY() + deltaY);
    }

    /** Centers the current panel within the viewport — used while auto-fit is active. */
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
     * Keeps the current panel from being panned/zoomed so far that empty viewport space appears on
     * both sides of an axis: if the panel is larger than the viewport along that axis, its edges are
     * pinned to the viewport edges instead of drifting past them; if it's smaller, it's re-centered
     * on that axis rather than left off to one side. Applied independently per axis.
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

        // Re-fetch bounds: the horizontal adjustment above may have shifted them.
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

    /** Bound to the "Gantt Chart" button in the FXML — switches the viewport to show {@link #ganttChart}. */
    @FXML
    private void showGanttChart() {
        switchToPanel(ganttChart);
    }

    /**
     * Swaps the viewport's content to {@code panel}, resets its pan/zoom state, and re-enables
     * auto-fit so the newly-shown panel starts centered and scaled to fit, regardless of whatever
     * pan/zoom state the previous panel was left in.
     */
    private void switchToPanel(StackPane panel) {
        currentPanel = panel;
        chartContainer.getChildren().setAll(currentPanel);
        StackPane.setAlignment(currentPanel, Pos.CENTER);

        autoFitEnabled = true;

        currentPanel.setTranslateX(0);
        currentPanel.setTranslateY(0);
        enforceMinScaleAndClamp();
        scheduleEnforceMinScaleAndClamp();

        // Fixes gantt chart snapping problem: a second deferred pass, since the Gantt chart's
        // preferred size can still be settling (e.g. just resized to fit a schedule) when the
        // immediate call above runs, leaving the first fit calculation stale.
        Platform.runLater(this::enforceMinScaleAndClamp);
    }

    /** Bound to the "Metrics" button in the FXML — switches the viewport to show {@link #metricsPanel}. */
    @FXML
    private void showMetrics() {
        currentPanel = metricsPanel;
        chartContainer.getChildren().setAll(currentPanel);
        StackPane.setAlignment(currentPanel, Pos.TOP_LEFT);
    }

    /** @return the Gantt chart panel, so {@link MainWindow} can push schedule updates into it. */
    public GanttChartPanel getGanttChart() {
        return ganttChart;
    }

    /** @return the metrics panel, so {@link MainWindow} can push search-progress updates into it. */
    public MetricsPanel getMetricsPanel() {
        return metricsPanel;
    }
}