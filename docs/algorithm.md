# Search algorithm

Package `se306.scheduler.algorithm` (WBS 3.1, 3.3). Source:
[`Algorithm`](../src/main/java/se306/scheduler/algorithm/Algorithm.java),
[`SequentialAlgorithm`](../src/main/java/se306/scheduler/algorithm/SequentialAlgorithm.java),
[`AbstractSearch`](../src/main/java/se306/scheduler/algorithm/AbstractSearch.java),
[`SearchContext`](../src/main/java/se306/scheduler/algorithm/SearchContext.java),
[`ListScheduler`](../src/main/java/se306/scheduler/algorithm/ListScheduler.java),
[`SearchMetrics`](../src/main/java/se306/scheduler/algorithm/metrics/SearchMetrics.java).
The multi-threaded variant lives in `se306.scheduler.algorithm.parallel` and is covered in
[parallel.md](parallel.md); everything on this page applies to it too, because both variants run
the same code in `AbstractSearch`.

## The problem

Given a [`TaskGraph`](graph-model.md) and `P` identical processors, assign every task a start time
and a processor so that:

1. every task starts after each of its predecessors has finished, plus the communication cost when
   the predecessor ran on a different processor,
2. no two tasks overlap on the same processor,

and the finish time of the last task (the **makespan**) is as small as possible. Finding the
minimum is NP-hard, so the algorithm is an exhaustive search made fast by pruning, not a heuristic:
the schedule it returns is provably optimal.

## Shape of the search

The search is a depth-first branch and bound over *partial schedules*. A node in the search tree is
a state where some tasks have been placed and the rest have not. From each node, the search
branches on every **ready** task (one whose predecessors are all placed) crossed with every
candidate processor. A leaf is a state with every task placed: a complete schedule, compared
against the best found so far. Four prunings (below) cut branches, but each one only removes
branches that are duplicates of a branch that survives, or that provably cannot beat the current
best. Every genuinely distinct, potentially-better schedule is still reached, which is why the
final answer is optimal.

Two classes drive this tree. `SequentialAlgorithm` walks it in place on one thread.
`ParallelSearch` walks the same tree but hands subtrees to other threads. All of the state,
the placement logic and the prunings are shared in their common base class, `AbstractSearch`.

## One mutable state, with an undo log

`AbstractSearch` does not copy the schedule at each node. It keeps a single mutable state and
mutates it on the way down, undoing on the way back up, so the sequential search allocates almost
nothing per node.

| Field                  | One entry per | Meaning                                             |
| ---------------------- | ------------- | --------------------------------------------------- |
| `processorOf[t]`       | task          | Processor `t` is placed on, `-1` if unplaced        |
| `startTime[t]`         | task          | Start time of `t`, `-1` if unplaced                 |
| `processorFreeAt[p]`   | processor     | When processor `p` finishes its last placed task    |
| `indegreeRemaining[t]` | task          | Unplaced predecessors of `t`; `0` means ready       |
| `taskCountOn[p]`       | processor     | How many tasks are on `p` (used by the symmetry pruning) |

Alongside these are a handful of scalars: `scheduledCount`, the running `makespan`, the running
critical-path bound `currentBound`, the committed `idleTime`, and `lastPlaced` (the task placed
most recently on this DFS path, used by the duplicate pruning).

`place(task, processor)` computes the earliest legal start, pushes a `LogEntry` recording the
values it is about to change, then commits: sets the task's processor and start, advances
`processorFreeAt`, updates the makespan, the bound and the idle time, and decrements
`indegreeRemaining` for each child (which is how children become ready). `undo()` pops the log
entry and restores every one of those values exactly. The DFS is `place`, recurse, `undo`.

The earliest legal start is the heart of the scheduling semantics, and the same rule appears in
`ListScheduler` and in the tests' reference solver:

```
earliestStart(task, p) = max( processorFreeAt[p],
                              max over predecessors q of:
                                  finish(q) + (0 if q is on p, else commCost(q, task)) )
```

Tasks are only ever appended at or after `processorFreeAt[p]`. The search never goes back and
fills a gap it left earlier, which is safe because some branch of the tree places the tasks in the
order that would have filled that gap, and it is also what makes the idle-time bound below valid.

## Seeding: run the greedy scheduler first

Bound pruning is only as strong as the best schedule known so far, so before the exact search
starts, `SearchContext.runGreedyAlgorithm()` produces a good one cheaply. `ListScheduler` walks
the tasks in a fixed topological order and puts each on the processor where it can start earliest.
That is valid but not necessarily optimal (on its own it was the Milestone 1 deliverable). It runs
twice, once in plain topological order and once in the critical-path priority order described
next, and the better result seeds `best`. From the very first node of the exact search, every
bound check already has a realistic target to prune against.

## Visit order: critical path first

`SearchContext` precomputes each task's **bottom level**: the longest chain of task weights from
that task to an exit task, including its own weight, ignoring communication. Ignoring
communication is what keeps it usable as a lower bound later; no schedule can beat it.

`getTaskPriorityOrder()` is all tasks sorted by descending bottom level, with ties broken by
topological position so a parent always sorts before an equal child. The search loops over ready
tasks in this order, which sends the DFS down critical-path branches first. Near-optimal schedules
are found early, `best` drops quickly, and every later bound check prunes harder. This changes
only the order branches are visited, never which branches exist, so it cannot affect correctness.

## The lower bound

At every node, `lowerBound()` is the largest of three quantities. If it is at least `best`, no
completion of this partial schedule can improve on what is already known, and the whole subtree is
abandoned.

1. **The committed makespan.** Work already placed cannot un-happen.
2. **The critical-path bound** (`currentBound`): for every placed task, its start time plus its
   bottom level is a chain of work that must run somewhere. The maximum over placed tasks is
   maintained incrementally: `place` raises it, the log entry restores it on `undo`.
3. **The idle-aware load bound** (`SearchContext.getLoadBound(idleTime)`): the total weight of all
   tasks is fixed, and every gap the schedule has already committed to (a task starting after its
   processor fell free) can never be filled, because tasks are only appended. So `P` timelines
   must fit `totalWork + idleTime` units, giving `makespan >= ceil((totalWork + idleTime) / P)`.
   The static version of this bound (with zero idle) holds before anything is placed at all; the
   idle term makes it grow as a branch commits to bad gaps, so it starts pruning where the static
   bound cannot.

## The prunings

| Pruning | Where | What it removes |
| ------- | ----- | --------------- |
| Lower bound vs best | `search()` on node entry | Subtrees that provably cannot beat `best` |
| Pre-place bound check | `exploreSequentially` / `isDoomed` | Placements doomed before they are even made |
| Processor symmetry | `exploreSequentially` / `processorLimit` | Placements onto a second, third, ... empty processor |
| Permutation duplicates | `isPermutationDuplicate` | Reorderings of independent placements |

- **Pre-place bound check.** Before placing, the search computes the task's earliest start on that
  processor and asks whether `start + bottomLevel(task) >= best`. If so, the very first bound
  check inside the child would kill it anyway, so the search skips the placement entirely. That
  saves the log push, the child-indegree updates and the undo, and in the parallel search it saves
  cloning a whole child object for a branch that was never going anywhere.
- **Processor symmetry.** Empty processors are interchangeable: placing a task on the second empty
  processor produces the same schedule as placing it on the first, up to relabelling. So only the
  first empty processor is ever tried, and the loop over processors stops there.
- **Permutation duplicates.** If the previous placement and this one touch different processors
  and have no dependency between them, the two decisions commute: the sibling branch that makes
  them in the opposite order reaches a bit-for-bit identical state. Only the canonical order (the
  lower-index task placed first) is kept. Because the rule is "keep exactly the canonical order",
  the two orders can never prune each other, and every reachable state survives in exactly one
  branch.

## Counting work: `SearchMetrics`

Every node visited counts as one *explored* branch; every branch cut by any pruning counts as one
*pruned*. These feed the [visualisation](gui.md), and they are the only thing the search reports
about itself.

The counters are designed to cost nearly nothing. Each search object accumulates counts in two
plain `long` fields and flushes them to shared `LongAdder`s in batches of 1024
(`COUNTER_FLUSH_INTERVAL`), plus once more when it finishes so the trailing partial batch is kept.
Touching a shared counter per node was by far the most expensive thing the metrics used to do;
batching cuts that traffic by three orders of magnitude while the GUI's periodic reading stays
accurate to within one batch per live thread. The expensive readings, process CPU load and heap
usage, happen only inside `SearchMetrics.snapshot()`, which only the GUI timer calls. A headless
run pays for nothing beyond the two `LongAdder`s.

## `SearchContext`: what the whole search shares

One `SearchContext` exists per run. Almost all of it is immutable input computed up front: the
graph, the bottom levels, the priority order, the total work, the metrics object and the optional
`SearchListener`. The only mutable part is the pair `best` and `bestSchedule`.

`compareAndSetBestSchedule` is written so that the common case, a leaf that does not improve on
`best`, costs one volatile read and nothing else. When a leaf does improve, the `Schedule` object
is constructed outside the lock (the arrays belong to the calling branch alone), the update is
double-checked inside a small `synchronized` block, and the listener is notified after the lock is
released so no worker can block behind a GUI callback. This is also the *only* event the GUI is
ever pushed; everything else it displays is sampled (see [gui.md](gui.md)). The full concurrency
story is in [parallel.md](parallel.md).

## The sequential driver

`SequentialAlgorithm.solve()` is the whole thing in four lines: seed `best` with the greedy
passes, run `search()` in place on the calling thread, flush the trailing metrics batch, return
`ctx.getBestSchedule()`. It extends `RecursiveAction` (via `AbstractSearch`) only because the
parallel search needs that superclass; the sequential search never calls `fork()` or `join()`.

## Tests

- [`DFSBranchAndBoundTest`](../src/test/java/se306/scheduler/schedule/DFSBranchAndBoundTest.java)
  checks the behavioural basics by example: single tasks, independent tasks in parallel and
  serialised, chains, and communication costs both keeping a task local and allowing a fork to
  spread out.
- [`BranchAndBoundOptimalityTest`](../src/test/java/se306/scheduler/schedule/BranchAndBoundOptimalityTest.java)
  is the heavyweight one: it cross-checks the search against an independent brute-force reference
  solver that enumerates *every* valid task order and processor assignment. It covers
  hand-computed optima (chains, diamonds with cheap and expensive communication, the project spec's
  Figure 1), adversarial graphs aimed at known branch-and-bound failure modes (asymmetric
  communication costs, heavy siblings on the critical path), many random DAGs, and soundness of
  the lower bound at every depth of the tree.
- [`AlgorithmTest`](../src/test/java/se306/scheduler/schedule/AlgorithmTest.java) runs the
  sequential and parallel searches on the same graphs and asserts they agree on the optimal
  makespan and both return valid schedules.
- [`AbstractSearchTest`](../src/test/java/se306/scheduler/algorithm/AbstractSearchTest.java)
  exercises readiness and the undo log directly: tasks become ready in dependency order, and a
  backtracked task can be placed again.
- [`SearchContextTest`](../src/test/java/se306/scheduler/algorithm/SearchContextTest.java) checks
  the bottom-level computation and the best-schedule handover, including that a non-improving
  schedule is ignored and not announced.
- [`AlgorithmListenerTest`](../src/test/java/se306/scheduler/algorithm/AlgorithmListenerTest.java)
  checks that both searches announce a strictly improving sequence of schedules, ending on the
  answer.
- [`ListSchedulerTest`](../src/test/java/se306/scheduler/schedule/ListSchedulerTest.java) covers
  the greedy seeding scheduler on the same style of small examples.
