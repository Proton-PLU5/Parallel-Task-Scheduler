package se306.scheduler.gui;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

public interface SearchListener {
    void onNewBestSchedule(TaskGraph graph, Schedule schedule);
}
