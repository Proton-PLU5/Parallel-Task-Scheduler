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
java -jar target/scheduler.jar src/test/resources/example.dot 2
```

## Layout

| Package | Responsibility | WBS |
|---|---|---|
| `se306.scheduler.io` | `DotParser` reads the input graph, `DotOutputWriter` writes the scheduled one | 2.2, 2.4 |
| `se306.scheduler.graph` | `GraphBuilder` accumulates declarations, `TaskGraph` is the frozen search-time model | 2.3 |
| `se306.scheduler.schedule` | `Schedule` — the engine's result and the writer's input | 3.x |
| `se306.scheduler.cli` | `CommandLineArgs` | 2.1 |

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

The parser, graph model, output writer and CLI are complete and tested. `Main.schedule` is still a
placeholder that runs every task sequentially on one processor: a valid schedule, but not an optimal
one, and it ignores `P`. Replacing it with the branch-and-bound search is WBS 3.x; the `Schedule` it
returns is the interface.