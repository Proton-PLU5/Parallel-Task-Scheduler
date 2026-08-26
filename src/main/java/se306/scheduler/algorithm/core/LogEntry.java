package se306.scheduler.algorithm.core;

public record LogEntry(int task, int processor, int previousFreeAt, int previousMakespan, int previousBound,
            int previousLastPlaced) {}
