package se306.scheduler.gui;

import javafx.application.Platform;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

public class MainWindow implements SearchListener {

    private final GanttChartPanel ganttChart;
    private final SearchTreePanel searchTree;

    public MainWindow(GanttChartPanel ganttChart, SearchTreePanel searchTree) {
        this.ganttChart = ganttChart;
        this.searchTree = searchTree;
    }

    // This calls the gantt chart to update; live updates
    @Override
    public void onNewBestSchedule(TaskGraph graph, Schedule schedule) {
        Platform.runLater(() -> ganttChart.renderSchedule(graph, schedule));
        // TODO: search tree will be called the same way
    }
}