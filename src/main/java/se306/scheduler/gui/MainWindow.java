package se306.scheduler.gui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import se306.scheduler.algorithm.SearchContext;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

public class MainWindow implements SearchListener {

    private static final Duration POLL_INTERVAL = Duration.millis(200);

    private final GanttChartPanel ganttChart;
    private final SearchTreePanel searchTree;
    private final MetricsPanel metricsPanel;

    private final Runtime runtime = Runtime.getRuntime();
    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private SearchContext monitoredContext;
    private long monitoringStartTime;
    private Timeline pollingTimeline;

    public MainWindow(GanttChartPanel ganttChart, SearchTreePanel searchTree, MetricsPanel metricsPanel) {
        this.ganttChart = ganttChart;
        this.searchTree = searchTree;
        this.metricsPanel = metricsPanel;
    }

    // This calls the gantt chart to update; live updates
    @Override
    public void onNewBestSchedule(TaskGraph graph, Schedule schedule) {
        Platform.runLater(() -> ganttChart.renderSchedule(graph, schedule));
        // TODO: search tree will be called the same way
    }

    /**
     * Starts periodically polling the search's shared state and JVM stats, pushing the
     * results into the metrics panel. Branch counters and best makespan aren't event-driven
     * like onNewBestSchedule because branches happen far too often to push a GUI update on
     * every one; polling on a timer keeps the UI responsive.
     */
    public void startMonitoring(SearchContext ctx) {
        monitoredContext = ctx;
        monitoringStartTime = System.currentTimeMillis();

        Platform.runLater(() -> {
            pollingTimeline = new Timeline(new KeyFrame(POLL_INTERVAL, event -> pushMetricsSnapshot()));
            pollingTimeline.setCycleCount(Timeline.INDEFINITE);
            pollingTimeline.play();
        });
    }

    /**
     * Stops the periodic polling started by startMonitoring. Takes one final snapshot first,
     * since a search that finishes faster than POLL_INTERVAL would otherwise leave the panel
     * showing its untouched placeholder values instead of the real result.
     */
    public void stopMonitoring() {
        Platform.runLater(() -> {
            if (pollingTimeline != null) {
                pollingTimeline.stop();
            }
            pushMetricsSnapshot();
            metricsPanel.markComplete();
        });
    }

    private void pushMetricsSnapshot() {
        long elapsedMillis = System.currentTimeMillis() - monitoringStartTime;
        long usedMemoryBytes = runtime.totalMemory() - runtime.freeMemory();
        double cpuLoadPercent = osBean.getProcessCpuLoad() * 100;

        metricsPanel.update(
                monitoredContext.getBranchesExplored(),
                monitoredContext.getBranchesPruned(),
                monitoredContext.getBest(),
                elapsedMillis,
                usedMemoryBytes,
                cpuLoadPercent);
    }
}