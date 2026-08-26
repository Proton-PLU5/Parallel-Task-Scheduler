package se306.scheduler.gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Bridges {@code Main}'s plain, non-JavaFX code to the JavaFX runtime.
 *
 * <p>JavaFX requires {@link Application#launch} to be called once and to control the lifecycle of
 * the {@link Application} it creates — callers can't just construct a {@code MainWindow}-like object
 * themselves. {@link #launchAndGetWindow} hides that constraint: it starts the JavaFX runtime on its
 * own thread and blocks the calling thread only until the window has been built, then hands back a
 * plain {@link MainWindow} object that the rest of the program (in particular the search algorithm,
 * via {@link SearchListener}) can use normally, with no further JavaFX-specific handling required.
 */
public class JavaFXLauncher extends Application {

    /** Set once, inside {@link #start}, then read back by {@link #launchAndGetWindow}. */
    private static MainWindow createdWindow;

    /**
     * Starts the JavaFX application on a dedicated thread and blocks the calling thread until the
     * window has finished being built, then returns the resulting {@link MainWindow}.
     *
     * @param args the program's command-line arguments, forwarded to {@link Application#launch}
     * @return the {@link MainWindow} created by {@link #start}, ready to be used as a {@link SearchListener}
     */
    public static MainWindow launchAndGetWindow(String[] args) {
        Thread t = new Thread(() -> Application.launch(JavaFXLauncher.class, args));
        t.setDaemon(false);
        t.start();

        // Block until start() has run on the JavaFX thread and published createdWindow.
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

    /**
     * Called by the JavaFX runtime once it has started. Loads the window's FXML layout, wraps its
     * controller's panels in a {@link MainWindow}, shows the stage, and then publishes the new
     * {@link MainWindow} so {@link #launchAndGetWindow} can return it to the waiting caller.
     */
    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/MainWindow.fxml"));
        Parent root = loader.load();
        MainWindowController controller = loader.getController();

        MainWindow window = new MainWindow(
                controller.getGanttChart(), controller.getMetricsPanel());

        Scene scene = new Scene(root, 1280, 720);
        primaryStage.setScene(scene);
        scene.getStylesheets().add(getClass().getResource("/main-window.css").toExternalForm());
        primaryStage.setTitle("Scheduler Visualizer");
        primaryStage.setResizable(false);
        primaryStage.show();

        // Ensure closing the window terminates the whole JVM, including the search thread
        // (which is not a daemon thread and would otherwise keep the process alive).
        primaryStage.setOnCloseRequest(event -> {
            Platform.exit();
            System.exit(0);
        });

        // Publish the finished window and wake up whichever thread is blocked in launchAndGetWindow.
        synchronized (JavaFXLauncher.class) {
            createdWindow = window;
            JavaFXLauncher.class.notifyAll();
        }
    }
}