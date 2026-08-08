package se306.scheduler.gui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.geometry.VPos;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

public class GanttChartPanel extends Canvas {

    private static final int PIXELS_PER_UNIT = 30;   // vertical scale: time
    private static final int COLUMN_WIDTH = 100;      // horizontal: one column per processor
    private static final int LEFT_MARGIN = 50;        // space for time-axis labels
    private static final int TOP_MARGIN = 30;          // space for processor headers

    public GanttChartPanel(double width, double height) {
        super(width, height);
    }

    public void renderSchedule(TaskGraph graph, Schedule schedule) {
        GraphicsContext gc = getGraphicsContext2D();
        gc.clearRect(0, 0, getWidth(), getHeight());

        int numProcessors = schedule.numProcessors();
        double chartRight = LEFT_MARGIN + numProcessors * COLUMN_WIDTH;

        // Processor column headers
        gc.setFill(Color.BLACK);
        gc.setTextAlign(TextAlignment.CENTER);
        for (int p = 0; p < numProcessors; p++) {
            double colX = LEFT_MARGIN + p * COLUMN_WIDTH + COLUMN_WIDTH / 2.0;
            gc.fillText("P" + (p + 1), colX, TOP_MARGIN - 10);
        }

        // Time axis: full-width gridlines + labels
        int maxTime = schedule.makespan();
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setTextBaseline(VPos.CENTER);
        gc.setStroke(Color.LIGHTGRAY);
        for (int t = 0; t <= maxTime; t += 2) {
            double y = TOP_MARGIN + t * PIXELS_PER_UNIT;
            gc.strokeLine(LEFT_MARGIN, y, chartRight, y);   // full-width now, not just a 5px tick
            gc.setFill(Color.BLACK);
            gc.fillText(String.valueOf(t), LEFT_MARGIN - 8, y);
        }

        // Task bars, now with visible borders
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        for (int t = 0; t < schedule.taskCount(); t++) {
            int proc = schedule.processor(t);
            int start = schedule.startTime(t);
            int duration = graph.weight(t);

            double x = LEFT_MARGIN + proc * COLUMN_WIDTH + 10;
            double y = TOP_MARGIN + start * PIXELS_PER_UNIT;
            double w = COLUMN_WIDTH - 20;
            double h = duration * PIXELS_PER_UNIT;

            gc.setFill(Color.STEELBLUE);
            gc.fillRect(x, y, w, h);

            gc.setStroke(Color.DARKSLATEGRAY);
            gc.setLineWidth(1.5);
            gc.strokeRect(x, y, w, h);   // border around each block

            gc.setFill(Color.WHITE);
            gc.fillText(graph.name(t), x + 5, y + 5);
        }
    }
}