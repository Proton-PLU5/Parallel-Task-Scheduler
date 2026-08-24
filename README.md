# Parallel Task Scheduler

Schedules a task graph onto `P` homogeneous processors so that the last task finishes as early as
possible. Input and output are both DOT files.

## Build

Requires JDK 17 or newer and Maven.

```
mvn package        # runs the tests, produces target/scheduler.jar and a copy at ./scheduler.jar
mvn test           # tests only
```

## Run

```
java -Xmx4G -jar scheduler.jar INPUT.dot P [OPTION]...

  INPUT.dot   task graph to schedule, in DOT format
  P           number of processors to schedule onto

  -p N        run the search on N threads (default 1, sequential)
  -v          visualise the search as it runs
  -o OUTPUT   output file name (default INPUT-output.dot, beside the input)
```

```
$ java -jar scheduler.jar example.dot 2
Schedule[4 tasks, 2 processors, makespan=19] written to example-output.dot
```

Exit status is 0 on success, 1 if the input can't be read or the output can't be written, 2 for a
bad command line. Full details in [docs/cli.md](docs/cli.md).

## Layout

The pipeline is `CliArguments` → `DotParser` → `TaskGraph` → `Algorithm.solve()` → `Schedule` →
`DotOutputWriter`, wired together in `Main`.

| Package                    | Responsibility                                                          | WBS           | Docs |
| -------------------------- | ----------------------------------------------------------------------- | ------------- | ---- |
| `se306.scheduler.cli`      | `CliArguments` parses the command line                                  | 2.1           | [cli.md](docs/cli.md) |
| `se306.scheduler.io`       | `DotParser` reads the input graph, `DotOutputWriter` writes the scheduled one | 2.2, 2.4 | [dot-input.md](docs/dot-input.md), [dot-output.md](docs/dot-output.md) |
| `se306.scheduler.graph`    | `GraphBuilder` accumulates declarations, `TaskGraph` is the frozen search-time model | 2.3 | [graph-model.md](docs/graph-model.md) |
| `se306.scheduler.schedule` | `Schedule` — the search's result and the writer's input                 | 3.2           | |
| `se306.scheduler.algorithm` | `SequentialAlgorithm` and `ParallelAlgorithm`: DFS branch-and-bound over a `TaskGraph` | 3.1, 3.3, 3.4 | |
| `se306.scheduler.gui`      | The JavaFX window behind `-v`: Gantt chart, metrics, search tree        |               | |

**`TaskGraph`'s public API is the contract between the I/O work and the engine work — changing it
requires team agreement.** See [docs/graph-model.md](docs/graph-model.md).

## Documentation

- [Command-line interface](docs/cli.md) — usage, output naming, exit codes and messages, adding an option
- [DOT input](docs/dot-input.md) — accepted format, tolerated noise, how parsing works, errors
- [DOT output](docs/dot-output.md) — output format and conventions, quoting, round trip
- [Graph model](docs/graph-model.md) — `GraphBuilder` vs `TaskGraph`, validation, accessors

Class-level Javadoc covers *what* each class does; the docs above cover *how* and *why*.

## Tests

JUnit 5, run by `mvn test`. The sample graphs in the repository root (`example.dot`, `test2.dot`,
`test3.dot`) are test fixtures — don't move or rename them. GitHub Actions runs the suite on every
push to a branch other than `main`; `main` is updated through pull requests, whose branches are
already tested.
