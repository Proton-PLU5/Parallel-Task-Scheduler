package se306.scheduler.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.StringJoiner;

/**
 * Mutable, name-keyed accumulator used while parsing a DOT file, converted to the frozen
 * index-based {@link TaskGraph} by {@link #build()}.
 *
 * <p>Node names may appear in any order and an edge may reference a node whose own declaration line
 * comes later in the file, so edges are held as raw name pairs and only resolved to indices in
 * {@link #build()}. All validation happens there, in one place.
 */
public final class GraphBuilder {

    /** A dependency as it was written in the input, before names are resolved to indices. */
    public record Edge(String from, String to, int weight) { }

    private final Map<String, Integer> nodeWeights = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private String graphName = "graph";

    /** Records the digraph's name so the output writer can reproduce it. */
    public GraphBuilder graphName(String name) {
        this.graphName = name;
        return this;
    }

    /**
     * Declares a task and its execution time. Redeclaring a task with the same weight is tolerated
     * (DOT allows a node to be mentioned more than once); redeclaring it with a different weight is
     * an error.
     */
    public void addNode(String name, int weight) {
        if (weight < 0) {
            throw new GraphValidationException(
                    "task '" + name + "' has negative weight " + weight + ".");
        }
        Integer existing = nodeWeights.putIfAbsent(name, weight);
        if (existing != null && existing != weight) {
            throw new GraphValidationException("task '" + name + "' is declared twice with "
                    + "different weights (" + existing + " then " + weight + ").");
        }
    }

    /** Declares a dependency {@code from -> to} with the given communication cost. */
    public void addEdge(String from, String to, int weight) {
        if (weight < 0) {
            throw new GraphValidationException(
                    "edge '" + from + " -> " + to + "' has negative weight " + weight + ".");
        }
        edges.add(new Edge(from, to, weight));
    }

    /** Number of tasks declared so far. */
    public int nodeCount() {
        return nodeWeights.size();
    }

    /** Number of edges declared so far, before duplicates are collapsed. */
    public int edgeCount() {
        return edges.size();
    }

    /**
     * Validates the accumulated declarations and freezes them into a {@link TaskGraph}.
     *
     * @throws GraphValidationException if an edge references an undeclared task, a task depends on
     *                                  itself, an edge is declared twice with conflicting weights,
     *                                  or the graph contains a cycle
     */
    public TaskGraph build() {
        int n = nodeWeights.size();

        String[] names = new String[n];
        int[] weights = new int[n];
        Map<String, Integer> indexByName = new LinkedHashMap<>(Math.max(16, n * 2));

        int i = 0;
        for (Map.Entry<String, Integer> entry : nodeWeights.entrySet()) {
            names[i] = entry.getKey();
            weights[i] = entry.getValue();
            indexByName.put(entry.getKey(), i);
            i++;
        }

        // n x n is trivial memory at the graph sizes this project targets, and gives O(1) lookup.
        int[][] commCost = new int[n][n];
        boolean[][] hasEdge = new boolean[n][n];
        int[] childOffset = new int[n + 1];
        int[] parentOffset = new int[n + 1];
        int edgeTotal = 0;

        for (Edge edge : edges) {
            Integer from = indexByName.get(edge.from());
            Integer to = indexByName.get(edge.to());
            if (from == null) {
                throw new GraphValidationException("edge '" + edge.from() + " -> " + edge.to()
                        + "' references undeclared task '" + edge.from() + "'.");
            }
            if (to == null) {
                throw new GraphValidationException("edge '" + edge.from() + " -> " + edge.to()
                        + "' references undeclared task '" + edge.to() + "'.");
            }
            if (from.equals(to)) {
                throw new GraphValidationException("task '" + edge.from() + "' depends on itself.");
            }
            if (hasEdge[from][to]) {
                if (commCost[from][to] != edge.weight()) {
                    throw new GraphValidationException("edge '" + edge.from() + " -> " + edge.to()
                            + "' is declared twice with different weights (" + commCost[from][to]
                            + " then " + edge.weight() + ").");
                }
                continue;
            }
            hasEdge[from][to] = true;
            commCost[from][to] = edge.weight();
            // Counts are gathered one slot high, then turned into offsets by the prefix sum below.
            childOffset[from + 1]++;
            parentOffset[to + 1]++;
            edgeTotal++;
        }

        for (int t = 0; t < n; t++) {
            childOffset[t + 1] += childOffset[t];
            parentOffset[t + 1] += parentOffset[t];
        }

        int[] childTargets = new int[edgeTotal];
        int[] parentTargets = new int[edgeTotal];
        int[] fill = new int[n];

        // Row-major over the edge matrix, so each task's successors come out ascending.
        for (int from = 0; from < n; from++) {
            for (int to = 0; to < n; to++) {
                if (hasEdge[from][to]) {
                    childTargets[childOffset[from] + fill[from]++] = to;
                }
            }
        }
        java.util.Arrays.fill(fill, 0);
        // Column-major, so each task's predecessors come out ascending.
        for (int to = 0; to < n; to++) {
            for (int from = 0; from < n; from++) {
                if (hasEdge[from][to]) {
                    parentTargets[parentOffset[to] + fill[to]++] = from;
                }
            }
        }

        int[] topologicalOrder = topologicalSort(names, childTargets, childOffset, parentOffset);

        return new TaskGraph(graphName, names, weights, childTargets, childOffset, parentTargets,
                parentOffset, commCost, topologicalOrder, Map.copyOf(indexByName));
    }

    /**
     * Kahn's algorithm, which produces the topological order and detects cycles in one pass. Ties
     * are broken by lowest index so the ordering — and therefore output — is reproducible.
     */
    private static int[] topologicalSort(String[] names, int[] childTargets, int[] childOffset,
                                         int[] parentOffset) {
        int n = names.length;
        int[] inDegree = new int[n];
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int t = 0; t < n; t++) {
            inDegree[t] = parentOffset[t + 1] - parentOffset[t];
            if (inDegree[t] == 0) {
                ready.add(t);
            }
        }

        int[] order = new int[n];
        int placed = 0;
        while (!ready.isEmpty()) {
            int task = ready.poll();
            order[placed++] = task;
            for (int k = childOffset[task]; k < childOffset[task + 1]; k++) {
                if (--inDegree[childTargets[k]] == 0) {
                    ready.add(childTargets[k]);
                }
            }
        }

        if (placed != n) {
            StringJoiner stuck = new StringJoiner(", ");
            for (int t = 0; t < n; t++) {
                if (inDegree[t] > 0) {
                    stuck.add(names[t]);
                }
            }
            throw new GraphValidationException(
                    "input graph contains a cycle; a task graph must be acyclic. "
                            + "Tasks involved in or downstream of the cycle: " + stuck + ".");
        }
        return order;
    }
}