package se306.scheduler.gui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import se306.scheduler.algorithm.SearchContext;
import se306.scheduler.algorithm.metrics.SearchMetrics;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.gui.gantt.GanttChartPanel;
import se306.scheduler.gui.metrics.MetricsPanel;
import se306.scheduler.schedule.Schedule;

/** Monitors and samples data from the SearchContext and calls the GanttChart and MetricsPanel to update with.*/
public class MainWindow implements SearchListener {

    /**
     * How often the status of the search is sampled. The panel owns this interval because its
     * replay pacing is derived from it. The search never records anything itself, so this timer is
     * the only thing that reads process CPU and heap.
     */
    private static final Duration SAMPLE_INTERVAL = MetricsPanel.SAMPLE_INTERVAL;

    private final GanttChartPanel ganttChart;
    private final MetricsPanel metricsPanel;

    private SearchContext monitoredContext;
    private Timeline sampleTimeline;

    public MainWindow(GanttChartPanel ganttChart, MetricsPanel metricsPanel) {
        this.ganttChart = ganttChart;
        this.metricsPanel = metricsPanel;
    }

    /**
     * Fired from a worker thread whenever the search reaches a leaf that beats the current best.
     * This is the only event the metrics panel receives from the algorithm: it drives the steps of
     * the convergence staircase, while everything else the panel shows comes from the sampler.
     *
     * <p>The elapsed time is read here rather than inside the {@code runLater} body, so a busy FX
     * thread delays when the improvement is drawn but never when it is recorded as having happened.
     */
    @Override
    public void onNewBestSchedule(TaskGraph graph, Schedule schedule) {
        double elapsedSeconds = monitoredContext == null ? 0 : monitoredContext.getMetrics().elapsedSeconds();
        int makespan = schedule.makespan();

        Platform.runLater(() -> {
            ganttChart.renderSchedule(graph, schedule);
            metricsPanel.recordImprovement(elapsedSeconds, makespan);
        });
    }

    /**
     * Starts sampling the search's status once a second into the metrics panel's live view.
     */
    public void startMonitoring(SearchContext ctx) {
        // Assigned before the timeline starts, and before solve() runs, so neither the sampler nor
        // an early improvement callback can observe a null context.
        monitoredContext = ctx;

        Platform.runLater(() -> {
            metricsPanel.beginRun();
            sampleTimeline = new Timeline(new KeyFrame(SAMPLE_INTERVAL, event -> sampleNow()));
            sampleTimeline.setCycleCount(Timeline.INDEFINITE);
            sampleTimeline.play();
        });
    }

    /**
     * Stops the sampler, takes one last reading so the final state is always represented, and
     * switches the panel from live-follow into scrubbable replay.
     */
    public void stopMonitoring() {
        Platform.runLater(() -> {
            if (sampleTimeline != null) {
                sampleTimeline.stop();
            }
            sampleNow();
            metricsPanel.markComplete();
        });
    }

    private void sampleNow() {
        if (monitoredContext == null) {
            return;
        }
        SearchMetrics.Snapshot snapshot = monitoredContext.getMetrics().snapshot();
        metricsPanel.recordFrame(snapshot.elapsedNanos() / 1_000_000_000.0, snapshot);
    }
}
