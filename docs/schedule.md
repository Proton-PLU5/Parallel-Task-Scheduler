# Schedule

Package `se306.scheduler.schedule` (WBS 3.2). Source:
[`Schedule`](../src/main/java/se306/scheduler/schedule/Schedule.java). The result type of the
whole program: the search produces one, the [DOT writer](dot-output.md) and the
[Gantt chart](gui.md) consume it.

## What it is

An immutable, complete assignment of every task in a `TaskGraph` to a start time and a processor.
It is indexed exactly like the graph it was built from: task `i` in the graph is task `i` here,
so no name lookups are needed anywhere downstream.

| Method            | Returns                                                        |
| ----------------- | -------------------------------------------------------------- |
| `startTime(task)` | When the task starts                                           |
| `processor(task)` | Which processor it runs on, numbered **`0..P-1`**              |
| `numProcessors()` | `P`, the processor count the schedule was built for            |
| `makespan()`      | Finish time of the last task: the value the search minimises   |
| `taskCount()`     | Number of tasks                                                |
| `toString()`      | One line, e.g. `Schedule[4 tasks, 2 processors, makespan=19]`  |

Processors are 0-based here and everywhere inside the program. The 1-based numbering required in
the output file is added by `DotOutputWriter` alone (see [dot-output.md](dot-output.md)).

`toString()` is what `Main` prints as the final line of a successful run, so its format is
effectively part of the CLI's contract (see [cli.md](cli.md#exit-status)).

## Construction and validation

```java
public Schedule(TaskGraph graph, int[] startTime, int[] processor, int numProcessors)
```

The constructor clones both arrays, so a caller (in particular a search branch that goes on
mutating its own state) can never change a `Schedule` after the fact. It then validates each
entry and computes the makespan in the same pass, rejecting with `IllegalArgumentException`:

| Problem                                    | Message                                                       |
| ------------------------------------------ | ------------------------------------------------------------- |
| Array length differs from the task count   | `startTime and processor must have exactly one entry per task.` |
| Processor outside `0..numProcessors-1`     | `task 'a' assigned to invalid processor 2 (numProcessors=2).` |
| Negative start time                        | `task 'a' has negative start time -1.`                        |

What it deliberately does **not** check: precedence (a task starting before a predecessor
finishes) and overlap (two tasks on one processor at the same time). Those are properties of how
the schedule was built, and checking them here would need the graph's edges on every construction,
including every improvement found mid-search. The constructor catches indexing and bookkeeping
bugs cheaply; the semantic guarantees are the search's job and are what the optimality tests
verify against a brute-force reference (see [algorithm.md](algorithm.md#tests)).

## Who creates one

- `SearchContext.compareAndSetBestSchedule` builds one for each strictly improving leaf the
  search reaches; the last of these is the answer `solve()` returns.
- `ListScheduler.solve()` builds the greedy seed schedules.
- Tests build them directly to check the writer and the validation above.

## Tests

[`ScheduleTest`](../src/test/java/se306/scheduler/schedule/ScheduleTest.java) covers: both
wrong-length array cases, a processor below 0 and one at `P`, a negative start time, the makespan
being the finish time of the task that finishes last, and the `toString` format.
