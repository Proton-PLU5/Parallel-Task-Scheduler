package se306.scheduler.gui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class JavaFXLauncher extends Application {

    private static MainWindow createdWindow;

    public static MainWindow launchAndGetWindow(String[] args) {
        Thread t = new Thread(() -> Application.launch(JavaFXLauncher.class, args));
        t.setDaemon(false);
        t.start();
        // Wait until start() has run and populated createdWindow
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
        GanttChartPanel ganttChart = new GanttChartPanel(600, 400);
        MainWindow window = new MainWindow(ganttChart);

        primaryStage.setScene(new Scene(new StackPane(ganttChart), 800, 500));
        primaryStage.setTitle("Scheduler Visualizer");
        primaryStage.show();

        synchronized (JavaFXLauncher.class) {
            createdWindow = window;
            JavaFXLauncher.class.notifyAll();
        }
    }
}