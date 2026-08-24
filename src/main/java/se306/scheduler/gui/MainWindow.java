package se306.scheduler.gui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import se306.scheduler.algorithm.SearchContext;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

public class MainWindow implements SearchListener {

    private static final Duration POLL_INTERVAL = Duration.millis(200);

    private final GanttChartPanel ganttChart;
    private final SearchTreePanel searchTree;
    private final MetricsPanel metricsPanel;

    private SearchContext monitoredContext;
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
     * Starts periodically polling the search's checkpoint log, pushing whatever's been recorded
     * so far into the metrics panel's live-follow view. Checkpoints are recorded on the algorithm
     * side (every 50,000 branches), so this is just checking in on that log, not sampling anything
     * itself.
     */
    public void startMonitoring(SearchContext ctx) {
        monitoredContext = ctx;

        Platform.runLater(() -> {
            pollingTimeline = new Timeline(
                    new KeyFrame(POLL_INTERVAL, event -> metricsPanel.updateLive(monitoredContext.getCheckpoints())));
            pollingTimeline.setCycleCount(Timeline.INDEFINITE);
            pollingTimeline.play();
        });
    }

    /**
     * Stops the periodic polling started by startMonitoring and hands the metrics panel the
     * final checkpoint log, switching it from live-follow into scrubbable replay mode.
     */
    public void stopMonitoring() {
        Platform.runLater(() -> {
            if (pollingTimeline != null) {
                pollingTimeline.stop();
            }
            metricsPanel.markComplete(monitoredContext.getCheckpoints());
        });
    }
}