# Parallel search

Package `se306.scheduler.algorithm.parallel` (WBS 3.4). Source:
[`ParallelAlgorithm`](../src/main/java/se306/scheduler/algorithm/parallel/ParallelAlgorithm.java),
[`ParallelSearch`](../src/main/java/se306/scheduler/algorithm/parallel/ParallelSearch.java);
the concurrency-aware pieces of
[`SearchContext`](../src/main/java/se306/scheduler/algorithm/SearchContext.java) and
[`AbstractSearch`](../src/main/java/se306/scheduler/algorithm/AbstractSearch.java) belong to this
story too. Selected by `-p N` with `N` greater than 1; read [algorithm.md](algorithm.md) first,
because the parallel search runs exactly the same search with the same bounds and prunings.

## Approach

The search tree is split across threads: independent subtrees are explored concurrently on a
work-stealing `ForkJoinPool` of `N` threads. `ParallelAlgorithm.solve()` seeds `best` with the
same greedy passes as the sequential driver, submits one root `ParallelSearch` to the pool with
`invoke` (which blocks until the whole tree is done), shuts the pool down, and returns the best
schedule from the shared context.

The guarantee is unchanged: the returned makespan is optimal. What *can* differ from run to run is
which of several equally-optimal schedules is returned, and how many branches get explored,
because pruning depends on how early each thread hears about improvements found by the others.
The tests therefore compare makespans, not schedules, across runs.

## How work is split

`ParallelSearch.exploreProcessors(task)` is where the parallel search differs from the sequential
one. For one ready task it must try each candidate processor; instead of trying them one after
another, it forks some of them:

1. `processorLimit()` caps the range first, so empty-processor duplicates are never even
   considered. (The sequential search handles this with a `break` after the first empty processor;
   here each fork covers a single processor, so the cap has to be computed up front.)
2. For each processor from 1 up to the cap, the permutation-duplicate and pre-place bound checks
   run **before** any forking. A branch that fails them is counted as pruned and skipped without
   paying for a child object or its cloned arrays.
3. A surviving branch is forked as a new `ParallelSearch` child, but only while this thread's own
   queue is not already deep (see the threshold below); otherwise it is explored in place, exactly
   as the sequential search would.
4. Processor 0 is always explored in place by the current thread, so the thread that split the
   node also does real work on one subtree rather than only spawning tasks.
5. Finally the forked children are joined, which in a work-stealing pool means this thread helps
   execute them if they have not been stolen.

The threshold: `getSurplusQueuedTaskCount()` reports how many tasks are queued locally beyond
what idle threads could steal. Past `SURPLUS_THRESHOLD` (3), creating more tasks would only add
allocation and queue traffic with no one free to steal them, so the search stays in-place until
the queue drains. This is what keeps the task count proportional to the number of threads instead
of the (astronomically larger) number of tree nodes.

## Why forking is safe

A forked child is built by the copy constructor in `AbstractSearch`: it clones all five state
arrays (`processorOf`, `startTime`, `processorFreeAt`, `indegreeRemaining`, `taskCountOn`) and
copies the scalar state, so parent and child can never see each other's mutations. Two details are
deliberate:

- The child gets a **fresh, empty undo log**. It is a new branch, so it never needs to undo state
  it inherited; it only undoes placements it made itself.
- The child does **not** inherit the parent's local metrics batch. The parent keeps ownership of
  everything it has counted; the child starts its own batch from zero. Each search flushes its
  final partial batch in `compute()`'s `finally`, so counts are never lost or double-counted.

After construction, the only thing parent and child share is the `SearchContext`.

## The shared best

`SearchContext` is the single piece of shared mutable state, and it is small on purpose:

- The graph, bottom levels, priority order and total work are computed once before any thread
  starts and never written again, so they are shared freely with no synchronisation.
- `best` is `volatile`. Every bound check in every thread reads it lock-free. A read can be
  momentarily stale, but that is harmless in exactly one direction: `best` only ever decreases, so
  a stale read is at worst too *large*, which can only delay a prune by a node or two. It can
  never prune a branch that should have survived, so correctness does not depend on timing.
- An improvement goes through `compareAndSetBestSchedule`: a lock-free volatile check rejects
  non-improvements immediately; the `Schedule` is constructed outside the lock from arrays the
  calling branch owns exclusively; a small `synchronized` block re-checks and swaps `best` and
  `bestSchedule` together; and the `SearchListener` (the GUI) is notified only after the lock is
  released, so no worker thread can ever block behind a paint.

One consequence surfaces in the GUI: because notification happens outside the lock, two
improvements can be *announced* in the opposite order to the one in which they took effect.
`MetricsHistory` filters stale announcements on its side (see [gui.md](gui.md)).

## Why `AbstractSearch` extends `RecursiveAction`

`ForkJoinPool` requires tasks to be `ForkJoinTask`s, and `RecursiveAction` is the result-free
kind. Making the common base class extend it means the sequential and parallel searches share
every line of the actual search; the alternative was a wrapper object per fork. The sequential
search simply never calls `fork()` or `join()`, and the unused machinery costs nothing.

## `N` and `P` are different numbers

`-p N` sets how many threads search; `P` sets how many processors the *schedule* uses. They are
independent: a 2-processor schedule can be searched on 8 threads. `N` larger than the machine's
core count is accepted but buys nothing. Each run creates its own pool and shuts it down when
`solve()` returns.

## Tests

- [`AlgorithmTest`](../src/test/java/se306/scheduler/schedule/AlgorithmTest.java) asserts the
  sequential and parallel searches agree on the optimal makespan across diamonds, chains and
  processor counts, and that both reject an invalid processor count.
- [`BranchAndBoundOptimalityTest`](../src/test/java/se306/scheduler/schedule/BranchAndBoundOptimalityTest.java)
  has a section where sequential and parallel results are both checked against the brute-force
  reference on random DAGs, plus the heavy-communication diamond specifically.
- [`AlgorithmListenerTest`](../src/test/java/se306/scheduler/algorithm/AlgorithmListenerTest.java)
  checks the parallel search announces strictly improving schedules and ends on the answer, which
  exercises the listener path under real concurrency.
