package se306.scheduler.graph;

import java.util.Map;

/**
 * Immutable, index-based representation of a task graph, built by {@link GraphBuilder#build()}.
 *
 * <p>Every task is identified by an {@code int} index in {@code [0, taskCount())}. Successors and
 * predecessors are held in <em>compressed sparse row</em> form: one flat array of all targets
 * concatenated, plus an offset array marking where each task's slice begins. That is a single
 * allocation instead of one array per task, and keeps a task's neighbours contiguous in memory for
 * the branch-and-bound search's hot loop:
 *
 * <pre>{@code
 * for (int k = g.childStart(i); k < g.childEnd(i); k++) {
 *     int child = g.childAt(k);
 *     ...
 * }
 * }</pre>
 *
 * <p>This type is deeply immutable once constructed and therefore safe to share across all search
 * threads with no synchronisation; only the search/schedule state needs thread-safe handling.
 *
 * <p>This class is the interface between the I/O work (WBS 2.x) and the engine work (WBS 3.x); its
 * public API is frozen and changes require team agreement.
 */
public final class TaskGraph {

    private final String graphName;
    private final String[] names;
    private final int[] weights;

    /** All successors concatenated; task {@code i} owns {@code [childOffset[i], childOffset[i+1])}. */
    private final int[] childTargets;
    private final int[] childOffset;

    /** All predecessors concatenated, indexed the same way. */
    private final int[] parentTargets;
    private final int[] parentOffset;

    private final int[][] commCost;
    private final int[] topologicalOrder;
    private final Map<String, Integer> indexByName;

    /** Only {@link GraphBuilder} may construct a graph, so validation cannot be bypassed. */
    TaskGraph(String graphName,
              String[] names,
              int[] weights,
              int[] childTargets,
              int[] childOffset,
              int[] parentTargets,
              int[] parentOffset,
              int[][] commCost,
              int[] topologicalOrder,
              Map<String, Integer> indexByName) {
        this.graphName = graphName;
        this.names = names;
        this.weights = weights;
        this.childTargets = childTargets;
        this.childOffset = childOffset;
        this.parentTargets = parentTargets;
        this.parentOffset = parentOffset;
        this.commCost = commCost;
        this.topologicalOrder = topologicalOrder;
        this.indexByName = indexByName;
    }

    /** Number of tasks; valid indices are {@code [0, taskCount())}. */
    public int taskCount() {
        return names.length;
    }

    /** Original DOT name of task {@code i}, with any surrounding quotes stripped. */
    public String name(int i) {
        return names[i];
    }

    /** Execution time of task {@code i}. */
    public int weight(int i) {
        return weights[i];
    }

    // --- successors -------------------------------------------------------------------------

    /** First position in the successor array belonging to task {@code i}. */
    public int childStart(int i) {
        return childOffset[i];
    }

    /** One past the last position in the successor array belonging to task {@code i}. */
    public int childEnd(int i) {
        return childOffset[i + 1];
    }

    /** The task index stored at successor position {@code k}, ascending within each task's slice. */
    public int childAt(int k) {
        return childTargets[k];
    }

    /** Number of successors of task {@code i}. */
    public int childCount(int i) {
        return childOffset[i + 1] - childOffset[i];
    }

    // --- predecessors -----------------------------------------------------------------------

    /** First position in the predecessor array belonging to task {@code i}. */
    public int parentStart(int i) {
        return parentOffset[i];
    }

    /** One past the last position in the predecessor array belonging to task {@code i}. */
    public int parentEnd(int i) {
        return parentOffset[i + 1];
    }

    /** The task index stored at predecessor position {@code k}, ascending within each slice. */
    public int parentAt(int k) {
        return parentTargets[k];
    }

    /** Number of predecessors of task {@code i}. Zero means it is an entry task. */
    public int parentCount(int i) {
        return parentOffset[i + 1] - parentOffset[i];
    }

    // --- edges ------------------------------------------------------------------------------

    /**
     * Communication cost incurred when {@code from} and {@code to} run on different processors.
     * Returns 0 when there is no {@code from -> to} edge; use {@link #hasEdge} to test for edge
     * existence, since a declared edge may legitimately have weight 0.
     */
    public int commCost(int from, int to) {
        return commCost[from][to];
    }

    /** Whether a dependency {@code from -> to} was declared. */
    public boolean hasEdge(int from, int to) {
        for (int k = childOffset[from]; k < childOffset[from + 1]; k++) {
            if (childTargets[k] == to) {
                return true;
            }
        }
        return false;
    }

    /** Total number of edges. */
    public int edgeCount() {
        return childTargets.length;
    }

    // --- whole-graph ------------------------------------------------------------------------

    /** A valid topological ordering of all tasks. Read-only — never modify the returned array. */
    public int[] topologicalOrder() {
        return topologicalOrder;
    }

    /** Index of the task with the given (unquoted) name, or -1 if there is no such task. */
    public int indexOf(String name) {
        Integer i = indexByName.get(name);
        return i == null ? -1 : i;
    }

    /** Sum of all task execution times — a trivial upper bound on any schedule's makespan. */
    public int totalWeight() {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        return total;
    }

    /** Name of the digraph as it appeared in the input, with any surrounding quotes stripped. */
    public String graphName() {
        return graphName;
    }

    @Override
    public String toString() {
        return "TaskGraph[" + graphName + ", " + taskCount() + " tasks, " + edgeCount() + " edges]";
    }
}