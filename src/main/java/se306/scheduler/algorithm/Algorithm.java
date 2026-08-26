package se306.scheduler.algorithm;

import se306.scheduler.algorithm.core.SearchContext;
import se306.scheduler.schedule.Schedule;

public interface Algorithm {

    public Schedule solve();

    public SearchContext getContext();
}
