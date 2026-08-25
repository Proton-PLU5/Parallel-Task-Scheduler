package se306.scheduler.gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
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
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/MainWindow.fxml"));
        Parent root = loader.load();
        MainWindowController controller = loader.getController();

        MainWindow window = new MainWindow(
                controller.getGanttChart(), controller.getSearchTree(), controller.getMetricsPanel());

        Scene scene = new Scene(root, 1280, 720);
        primaryStage.setScene(scene);
        scene.getStylesheets().add(getClass().getResource("/main-window.css").toExternalForm());
        primaryStage.setTitle("Scheduler Visualizer");
        primaryStage.setResizable(false);
        primaryStage.show();

        primaryStage.setOnCloseRequest(event -> {
            Platform.exit();
            System.exit(0);
        });

        synchronized (JavaFXLauncher.class) {
            createdWindow = window;
            JavaFXLauncher.class.notifyAll();
        }
    }
}