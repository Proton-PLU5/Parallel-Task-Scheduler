# Graph model

Package `se306.scheduler.graph` (WBS 2.3). Source:
[`GraphBuilder`](../src/main/java/se306/scheduler/graph/GraphBuilder.java),
[`TaskGraph`](../src/main/java/se306/scheduler/graph/TaskGraph.java),
[`GraphValidationException`](../src/main/java/se306/scheduler/graph/GraphValidationException.java).

**`TaskGraph`'s public API is the contract between the I/O work and the engine work; changing it
requires team agreement.**

## Two representations

Parsing and searching want different things from the graph, so they get different types.

`GraphBuilder` is mutable and keyed by name, because that is what a DOT file gives us: names in any
order, and edges that may mention a task before it is declared. Edges are kept as raw name pairs and
only resolved in `build()`, which is also where all validation happens.

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

## Indices and ordering

- A task's index is the order its `Weight` was declared in the input (first weighted declaration
  wins; a later identical redeclaration is ignored). `indexOf(name)` maps back.
- Each task's successors (`childAt`) and predecessors (`parentAt`) are in ascending index order.
- `topologicalOrder()` is Kahn's algorithm with ties broken by lowest index, so it is reproducible.
- Communication cost lookup `commCost(from, to)` is an `n × n` matrix: O(1) to read, and trivial
  memory at the sizes this project targets.

## Validation in `build()`

Everything the parser can't check by itself is checked here, in one place, and reported as a
`GraphValidationException` naming the task or edge involved:

| Problem                                               | Message                                                         |
| ----------------------------------------------------- | --------------------------------------------------------------- |
| Negative task or edge weight                          | `task 'a' has negative weight -1.`                              |
| Task declared twice with different weights            | `task 'a' is declared twice with different weights (1 then 2).` |
| Edge declared twice with different weights            | `edge 'a -> b' is declared twice with different weights (2 then 3).` |
| Edge endpoint never declared as a task                | `edge 'a -> b' references undeclared task 'b'.`                 |
| Self-loop                                             | `task 'a' depends on itself.`                                   |
| Cycle                                                 | `input graph contains a cycle; a task graph must be acyclic. Tasks involved in or downstream of the cycle: a, b.` |

Identical redeclarations (same task, same weight; same edge, same cost) are tolerated, because DOT
lets a node or edge be mentioned more than once.

## `TaskGraph` accessors

| Method                                   | Returns                                                   |
| ---------------------------------------- | --------------------------------------------------------- |
| `taskCount()`, `edgeCount()`             | Sizes                                                     |
| `graphName()`                            | The `digraph` name, `"graph"` if the input had none       |
| `name(i)`, `weight(i)`, `indexOf(name)`  | Task identity and execution time                          |
| `childStart(i)`, `childEnd(i)`, `childAt(k)`, `childCount(i)`     | Successors of `i`, via CSR   |
| `parentStart(i)`, `parentEnd(i)`, `parentAt(k)`, `parentCount(i)` | Predecessors of `i`, via CSR |
| `hasEdge(from, to)`, `commCost(from, to)` | Dependency and its communication cost                    |
| `topologicalOrder()`                     | A valid task order, reproducible                          |
| `totalWeight()`                          | Sum of all task weights                                   |
