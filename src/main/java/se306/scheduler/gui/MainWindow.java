package se306.scheduler.gui; // match your actual package

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class MainWindow extends Application {
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Scheduler Visualizer");
        primaryStage.setScene(new Scene(new StackPane(), 800, 500));
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}