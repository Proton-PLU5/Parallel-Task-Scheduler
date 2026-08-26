package se306.scheduler.gui;

import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.geometry.VPos;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

public class GanttChartPanel extends StackPane {

    private static final int PIXELS_PER_UNIT = 30;
    private static final int COLUMN_WIDTH = 100;
    private static final int LEFT_MARGIN = 90;
    private static final int TOP_MARGIN = 80;
    private static final int AXIS_TITLE_FONT_SIZE = 24;
    private static final int BODY_FONT_SIZE = 18;
    private static final int PROCESSOR_TITLE_X = 10;

    private final Canvas canvas;

    public GanttChartPanel(double width, double height) {
        canvas = new Canvas(width, height);
        getChildren().add(canvas);
        StackPane.setAlignment(canvas, Pos.CENTER);

        // Keep this panel at its preferred size so parent alignment can center it.
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        prefWidthProperty().bind(canvas.widthProperty());
        prefHeightProperty().bind(canvas.heightProperty());
    }

    public void renderSchedule(TaskGraph graph, Schedule schedule) {
        int numProcessors = schedule.numProcessors();
        int maxTime = schedule.makespan();

        double requiredWidth =
            LEFT_MARGIN + maxTime * PIXELS_PER_UNIT + 20;

        double requiredHeight =
            TOP_MARGIN + numProcessors * COLUMN_WIDTH;

        canvas.setWidth(requiredWidth);
        canvas.setHeight(requiredHeight);

        // Resize immediately to fix top left snapping problem
        resize(requiredWidth, requiredHeight);
        requestLayout();

        GraphicsContext gc = canvas.getGraphicsContext2D();

        gc.clearRect(
            0,
            0,
            canvas.getWidth(),
            canvas.getHeight()
        );

        // Axis titles
        gc.setFill(Color.WHITE);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(Font.font("Arial", AXIS_TITLE_FONT_SIZE));
        gc.fillText(
            "Time",
            LEFT_MARGIN + (maxTime * PIXELS_PER_UNIT) / 2.0,
            TOP_MARGIN - 50
        );

        gc.save();
        gc.translate(PROCESSOR_TITLE_X, TOP_MARGIN + (numProcessors * COLUMN_WIDTH) / 2.0);
        gc.rotate(-90);
        gc.fillText("Processors", 0, 0);
        gc.restore();

        // Use a smaller body font for tick labels and task labels.
        gc.setFont(Font.font("Arial", BODY_FONT_SIZE));

        // Processor labels
        gc.setFill(Color.WHITE);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);

        for (int p = 0; p < numProcessors; p++) {
            double y =
                TOP_MARGIN
                + p * COLUMN_WIDTH
                + COLUMN_WIDTH / 2.0;

            gc.fillText(
                "P" + (p + 1),
                LEFT_MARGIN / 2.0,
                y
            );
        }

        // Time axis
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setStroke(Color.WHITE);

        for (int t = 0; t <= maxTime; t += 2) {
            double x =
                LEFT_MARGIN
                + t * PIXELS_PER_UNIT;

            // Vertical gridline
            gc.strokeLine(
                x,
                TOP_MARGIN,
                x,
                TOP_MARGIN + numProcessors * COLUMN_WIDTH
            );

            // Time label
            gc.setFill(Color.WHITE);
            gc.fillText(
                String.valueOf(t),
                x,
                TOP_MARGIN - 15
            );
        }

        // Tasks
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);

        for (int t = 0; t < schedule.taskCount(); t++) {
            int proc = schedule.processor(t);
            int start = schedule.startTime(t);
            int duration = graph.weight(t);

            // Horizontal position = time
            double x =
                LEFT_MARGIN
                + start * PIXELS_PER_UNIT;

            // Vertical position = processor
            double y =
                TOP_MARGIN
                + proc * COLUMN_WIDTH
                + 10;

            // Width = duration
            double w =
                duration * PIXELS_PER_UNIT;

            // Height = processor row height
            double h =
                COLUMN_WIDTH - 20;

            gc.setFill(Color.STEELBLUE);
            gc.fillRect(x, y, w, h);

            gc.setStroke(Color.DARKSLATEGRAY);
            gc.setLineWidth(1.5);
            gc.strokeRect(x, y, w, h);

            gc.setFill(Color.WHITE);
            gc.fillText(
                graph.name(t),
                x + 5,
                y + h / 2
            );
        }
    }
}