package se306.scheduler.gui;

import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.Schedule;

// Callback the search algorithm uses to report a new best schedule as it finds one.
public interface SearchListener {
    void onNewBestSchedule(TaskGraph graph, Schedule schedule);
}
