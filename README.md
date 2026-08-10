# Parallel Task Scheduler

Schedules a task graph onto a fixed number of homogeneous processors so that the finish time of the
last task is as small as possible. Input and output are both DOT files.

## Build

Requires JDK 17 or newer (the build targets Java 17 bytecode) and Maven.

```
mvn package        # runs the tests and produces target/scheduler.jar
mvn test           # tests only
```

## Run

```
java -Xmx4G -jar target/scheduler.jar INPUT.dot P [OPTION]...

  INPUT.dot   task graph to schedule, in DOT format
  P           number of processors to schedule onto

Options:
  -p N        use N cores for execution in parallel (default 1)
  -v          visualise the search
  -o OUTPUT   output file name (default INPUT-output.dot)
```

For example:

```
java -jar target/scheduler.jar example.dot 2
```

## Layout

| Package                    | Responsibility                                                                       | WBS      |
| -------------------------- | ------------------------------------------------------------------------------------ | -------- |
| `se306.scheduler.io`       | `DotParser` reads the input graph, `DotOutputWriter` writes the scheduled one        | 2.2, 2.4 |
| `se306.scheduler.graph`    | `GraphBuilder` accumulates declarations, `TaskGraph` is the frozen search-time model | 2.3      |
| `se306.scheduler.schedule` | `Schedule` - the engine's result and the writer's input                              | 3.2      |
| `se306.scheduler.cli`      | `CliArguments` parses the command line                                               | 2.1      |

### The two graph representations

Parsing and searching want different things from the graph, so they get different types.

`GraphBuilder` is mutable and keyed by name, because that is what a DOT file gives us: names in any
order, and edges that may mention a task before it is declared. Edges are kept as raw name pairs and
only resolved in `build()`, which is also where all validation happens — undeclared endpoints,
self-loops, contradictory redeclarations and cycles.

`TaskGraph` is what `build()` produces: immutable, with every task reduced to an `int` index and all
structure held in flat arrays. Successors and predecessors are stored in compressed sparse row form
(one array of targets, one of offsets), so a task's neighbours are contiguous and the search does no
string hashing and no allocation on its hot path:

```java
for (int k = graph.childStart(task); k < graph.childEnd(task); k++) {
    int child = graph.childAt(k);
    ...
}
```

Because it is immutable it can be shared across every search thread with no synchronisation; only
the search state needs thread-safe handling.

**`TaskGraph`'s public API is the contract between the I/O work and the engine work — changing it
requires team agreement.**

## Status

Currently equivalent to the `Milestone 1` release - see below for details. This section will track
ongoing development once work moves past that tag.

## Milestone 1

Tagged `Milestone 1` on GitHub. See **Status** above for where the code currently stands relative
to this release.

**Scope:** read an input graph and processor count, produce a valid (not necessarily optimal) schedule.

The parser, graph model, output writer and CLI are complete and tested, and `Main` runs the whole
pipeline: read the input graph, schedule it, write the result to `-o OUTPUT` (or `INPUT-output.dot`).

The scheduler itself is `ListScheduler`, a greedy list scheduler that respects `P` and the
communication costs but is not optimal. Replacing it with the branch-and-bound search is WBS 3.1, 3.3, 3.4; the `Schedule` it returns is the interface, and nothing downstream of it needs to change.

Output tasks carry `Weight`, `Start` and `Processor`, with processors numbered `1..P` — `Schedule` numbers them from 0 internally, and `DotOutputWriter` is the only
place that conversion happens.

**Not yet implemented in this release:**

- No optimality guarantee (branch-and-bound search is WBS 3.1, 3.3, 3.4)
- `-p N` and `-v` are accepted on the command line but have no effect - there's no search yet to
  parallelise or visualise
