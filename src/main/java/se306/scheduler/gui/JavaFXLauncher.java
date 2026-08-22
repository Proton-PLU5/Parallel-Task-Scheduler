package se306.scheduler.gui;

import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.input.MouseButton;
import javafx.stage.Stage;

public class JavaFXLauncher extends Application {

    private static MainWindow createdWindow;

    public static MainWindow launchAndGetWindow(String[] args) {
        Thread t = new Thread(() -> Application.launch(JavaFXLauncher.class, args));
        t.setDaemon(false);
        t.start();
        synchronized (JavaFXLauncher.class) {
            while (createdWindow == null) {
                try {
                    JavaFXLauncher.class.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return createdWindow;
    }

    @Override
    public void start(Stage primaryStage) {
        GanttChartPanel ganttChart = new GanttChartPanel(1280, 720);
        MainWindow window = new MainWindow(ganttChart);

        StackPane chartContainer = new StackPane(ganttChart);
        StackPane.setAlignment(ganttChart, Pos.TOP_LEFT);
        chartContainer.setPickOnBounds(true);
        chartContainer.setStyle("-fx-background-color: #494949;");

        final double[] lastDragPosition = new double[2];
        chartContainer.setOnMousePressed(event -> {
            if (!event.isSynthesized() && event.getButton() == MouseButton.PRIMARY) {
                lastDragPosition[0] = event.getSceneX();
                lastDragPosition[1] = event.getSceneY();
            }
        });
        chartContainer.setOnMouseDragged(event -> {
            if (!event.isSynthesized() && event.isPrimaryButtonDown()) {
                ganttChart.setTranslateX(ganttChart.getTranslateX()
                    + event.getSceneX() - lastDragPosition[0]);
                ganttChart.setTranslateY(ganttChart.getTranslateY()
                    + event.getSceneY() - lastDragPosition[1]);
                lastDragPosition[0] = event.getSceneX();
                lastDragPosition[1] = event.getSceneY();
            }
        });

        primaryStage.setScene(new Scene(chartContainer, 1280, 720, Color.web("#494949")));
        primaryStage.setTitle("Scheduler Visualizer");
        primaryStage.setResizable(false);
        primaryStage.show();

        synchronized (JavaFXLauncher.class) {
            createdWindow = window;
            JavaFXLauncher.class.notifyAll();
        }
    }
}