package se306.scheduler.gui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

public class MainWindow extends Application {

    private static TaskGraph graph;
    private static Schedule schedule;

    public static void setSchedule(TaskGraph graph, Schedule schedule) {
        MainWindow.graph = graph;
        MainWindow.schedule = schedule;
    }

    @Override
    public void start(Stage primaryStage) {
        GanttChartPanel ganttChart = new GanttChartPanel(600, 400);
        if (schedule != null) {
            ganttChart.renderSchedule(graph, schedule);
        }

        primaryStage.setScene(new Scene(new StackPane(ganttChart), 800, 500));
        primaryStage.setTitle("Scheduler Visualizer");
        primaryStage.show();
    }
}