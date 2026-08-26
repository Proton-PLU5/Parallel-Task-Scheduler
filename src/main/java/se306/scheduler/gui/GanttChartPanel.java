package se306.scheduler.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.geometry.VPos;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

public class GanttChartPanel extends StackPane {

    private static final int PIXELS_PER_UNIT = 30;
    private static final int COLUMN_WIDTH = 72;
    private static final int LEFT_MARGIN = 90;
    private static final int TOP_MARGIN = 110;
    private static final int LEGEND_Y = 22;
    private static final int AXIS_TITLE_FONT_SIZE = 18;
    private static final int BODY_FONT_SIZE = 13;
    private static final int TASK_TITLE_FONT_SIZE = 14;
    private static final int TASK_SUBTITLE_FONT_SIZE = 11;
    private static final int LEGEND_FONT_SIZE = 13;
    private static final int PROCESSOR_TITLE_X = 10;
    private static final int TASK_CORNER_RADIUS = 10;
    private static final int BAR_VERTICAL_PADDING = 7;
    private static final int LEGEND_SWATCH_SIZE = 12;

    // Matches the palette used by main-window.css / MetricsPanel, plus a
    // job-family palette for coloring tasks by job instead of by processor.
    private static final Color TEXT_PRIMARY = Color.web("#ffffff");
    private static final Color TEXT_SECONDARY = Color.web("#c3c2b7");
    private static final Color GRID_LINE = Color.web("#383835", 0.35);
    private static final Color ROW_DIVIDER = Color.web("#383835", 0.22);
    private static final Color ROW_BAND = Color.web("#232221");
    private static final Color NEUTRAL = Color.web("#6b7280");
    private static final Color BAR_SUBTITLE = Color.web("#ffffff", 0.75);

    private static final String[] FAMILY_MID = {
            "#534AB7", "#0F6E56", "#993C1D", "#993556", "#854F0B", "#185FA5"
    };

    private static final Pattern TRAILING_DIGITS = Pattern.compile("\\d+$");

    private final Canvas canvas;

    public GanttChartPanel(double width, double height) {
        getStyleClass().add("gantt-chart-panel");
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

        double requiredWidth = LEFT_MARGIN + maxTime * PIXELS_PER_UNIT + 20;

        double requiredHeight = TOP_MARGIN + numProcessors * COLUMN_WIDTH;

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
                canvas.getHeight());

        double rowRight = requiredWidth - 10;

        // Assign each job family (task names sharing a letter prefix, e.g. "a1"/"a2")
        // its own color, in order of first appearance. "start"/"end" pseudo-tasks
        // share a neutral gray instead of taking a job color.
        Map<String, Color> familyColors = new LinkedHashMap<>();
        List<String> legendOrder = new ArrayList<>();
        boolean hasStartOrEnd = false;
        int paletteIndex = 0;

        for (int t = 0; t < schedule.taskCount(); t++) {
            String name = graph.name(t);
            if (isStartOrEnd(name)) {
                hasStartOrEnd = true;
                continue;
            }
            String key = familyKey(name);
            if (!familyColors.containsKey(key)) {
                familyColors.put(key, Color.web(FAMILY_MID[paletteIndex % FAMILY_MID.length]));
                legendOrder.add(key);
                paletteIndex++;
            }
        }

        // Legend
        Font legendFont = Font.font(null, FontWeight.BOLD, LEGEND_FONT_SIZE);
        gc.setFont(legendFont);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);

        double legendX = LEFT_MARGIN;
        if (hasStartOrEnd) {
            legendX = drawLegendItem(gc, legendX, LEGEND_Y, legendFont, NEUTRAL, "Start / end");
        }
        for (String key : legendOrder) {
            legendX = drawLegendItem(gc, legendX, LEGEND_Y, legendFont, familyColors.get(key), "Job " + key);
        }

        // Axis titles
        gc.setFill(TEXT_PRIMARY);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setFont(Font.font(null, FontWeight.BOLD, AXIS_TITLE_FONT_SIZE));
        gc.fillText(
                "Time",
                LEFT_MARGIN + (maxTime * PIXELS_PER_UNIT) / 2.0,
                TOP_MARGIN - 48);

        gc.save();
        gc.translate(PROCESSOR_TITLE_X, TOP_MARGIN + (numProcessors * COLUMN_WIDTH) / 2.0);
        gc.rotate(-90);
        gc.fillText("Processors", 0, 0);
        gc.restore();

        // Row banding- alternate rows get a faint tint so they stay easy to scan.
        for (int p = 1; p < numProcessors; p += 2) {
            double rowTop = TOP_MARGIN + p * COLUMN_WIDTH;
            gc.setFill(ROW_BAND);
            gc.fillRect(LEFT_MARGIN, rowTop, rowRight - LEFT_MARGIN, COLUMN_WIDTH);
        }

        // Use a smaller body font for tick labels and task labels.
        gc.setFont(Font.font(null, FontWeight.NORMAL, BODY_FONT_SIZE));

        // Processor labels
        gc.setFill(TEXT_SECONDARY);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);

        for (int p = 0; p < numProcessors; p++) {
            double y = TOP_MARGIN
                    + p * COLUMN_WIDTH
                    + COLUMN_WIDTH / 2.0;

            gc.fillText(
                    "P" + (p + 1),
                    LEFT_MARGIN / 2.0,
                    y);
        }

        // Row divider hairlines
        gc.setStroke(ROW_DIVIDER);
        gc.setLineWidth(1.0);
        for (int p = 0; p <= numProcessors; p++) {
            double y = TOP_MARGIN + p * COLUMN_WIDTH;
            gc.strokeLine(LEFT_MARGIN, y, rowRight, y);
        }

        // Time axis
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setStroke(GRID_LINE);
        gc.setLineWidth(1.0);

        for (int t = 0; t <= maxTime; t += 2) {
            double x = LEFT_MARGIN
                    + t * PIXELS_PER_UNIT;

            // Vertical gridline
            gc.strokeLine(
                    x,
                    TOP_MARGIN,
                    x,
                    TOP_MARGIN + numProcessors * COLUMN_WIDTH);

            // Time label
            gc.setFill(TEXT_SECONDARY);
            gc.fillText(
                    String.valueOf(t),
                    x,
                    TOP_MARGIN - 15);
        }

        // Tasks
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        Font titleFont = Font.font(null, FontWeight.BOLD, TASK_TITLE_FONT_SIZE);
        Font subtitleFont = Font.font(null, FontWeight.NORMAL, TASK_SUBTITLE_FONT_SIZE);

        for (int t = 0; t < schedule.taskCount(); t++) {
            String name = graph.name(t);
            int proc = schedule.processor(t);
            int start = schedule.startTime(t);
            int duration = graph.weight(t);
            int end = start + duration;

            // Horizontal position = time
            double x = LEFT_MARGIN
                    + start * PIXELS_PER_UNIT;

            // Vertical position = processor
            double y = TOP_MARGIN
                    + proc * COLUMN_WIDTH
                    + BAR_VERTICAL_PADDING;

            // Width = duration
            double w = duration * PIXELS_PER_UNIT;

            // Height = processor row height
            double h = COLUMN_WIDTH - 2 * BAR_VERTICAL_PADDING;

            Color barColor = isStartOrEnd(name) ? NEUTRAL : familyColors.get(familyKey(name));

            gc.setFill(barColor);
            gc.fillRoundRect(x, y, w, h, TASK_CORNER_RADIUS, TASK_CORNER_RADIUS);

            gc.setFont(titleFont);
            gc.setFill(TEXT_PRIMARY);
            gc.fillText(name, x + 8, y + h / 2.0 - 9);

            gc.setFont(subtitleFont);
            gc.setFill(BAR_SUBTITLE);
            gc.fillText(start + "-" + end, x + 8, y + h / 2.0 + 10);
        }
    }

    private static boolean isStartOrEnd(String name) {
        return name.equalsIgnoreCase("start") || name.equalsIgnoreCase("end");
    }

    private static String familyKey(String name) {
        String stripped = TRAILING_DIGITS.matcher(name).replaceAll("");
        return stripped.isEmpty() ? name : stripped;
    }

    private static double drawLegendItem(
            GraphicsContext gc, double x, double y, Font font, Color color, String label) {
        double radius = LEGEND_SWATCH_SIZE / 3.0;
        gc.setFill(color);
        gc.fillRoundRect(x, y - LEGEND_SWATCH_SIZE / 2.0, LEGEND_SWATCH_SIZE, LEGEND_SWATCH_SIZE, radius, radius);

        gc.setFill(TEXT_PRIMARY);
        gc.fillText(label, x + LEGEND_SWATCH_SIZE + 6, y);

        return x + LEGEND_SWATCH_SIZE + 6 + textWidth(label, font) + 22;
    }

    private static double textWidth(String text, Font font) {
        Text measurer = new Text(text);
        measurer.setFont(font);
        return measurer.getLayoutBounds().getWidth();
    }
}
