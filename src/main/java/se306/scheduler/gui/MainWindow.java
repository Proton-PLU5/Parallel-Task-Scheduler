package se306.scheduler.gui;

import javafx.application.Platform;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

public class MainWindow implements SearchListener {

    private final GanttChartPanel ganttChart;

    public MainWindow(GanttChartPanel ganttChart) {
        this.ganttChart = ganttChart;
    }

    @Override
    public void onNewBestSchedule(TaskGraph graph, Schedule schedule) {
        Platform.runLater(() -> ganttChart.renderSchedule(graph, schedule));
    }
}