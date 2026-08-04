package se306.scheduler.graph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.StringJoiner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se306.scheduler.io.DotParser;

/**
 * Prints a parsed {@link TaskGraph} rather than asserting on it — a readable dump of exactly what
 * the branch-and-bound search will be handed, for eyeballing during development.
 *
 * <p>Run just this class with {@code mvn test -Dtest=TaskGraphPrinterTest}.
 */
class TaskGraphPrinterTest {

    @Test
    @DisplayName("prints example.dot")
    void printExample() throws IOException {
        print(parse("example.dot"));
    }

    @Test
    @DisplayName("prints test2.dot")
    void printTest2() throws IOException {
        print(parse("test2.dot"));
    }

    private static TaskGraph parse(String fileName) throws IOException {
        return new DotParser().parse(Path.of(System.getProperty("basedir", ".")).resolve(fileName));
    }

    private static void print(TaskGraph g) {
        System.out.println();
        System.out.println("=========== " + g.graphName() + " ===========");
        System.out.println(g + ", total weight " + g.totalWeight());

        System.out.println("\ntasks");
        for (int t = 0; t < g.taskCount(); t++) {
            System.out.printf("  [%d] %-6s weight=%-4d parents=%-2d children=%d%n",
                    t, g.name(t), g.weight(t), g.parentCount(t), g.childCount(t));
        }

        System.out.println("\nedges");
        for (int t = 0; t < g.taskCount(); t++) {
            for (int k = g.childStart(t); k < g.childEnd(t); k++) {
                int child = g.childAt(k);
                System.out.printf("  %s -> %-6s cost=%d%n",
                        g.name(t), g.name(child), g.commCost(t, child));
            }
        }

        System.out.println("\nadjacency (via the CSR accessors)");
        for (int t = 0; t < g.taskCount(); t++) {
            StringJoiner parents = new StringJoiner(", ", "[", "]");
            for (int k = g.parentStart(t); k < g.parentEnd(t); k++) {
                parents.add(g.name(g.parentAt(k)));
            }
            StringJoiner children = new StringJoiner(", ", "[", "]");
            for (int k = g.childStart(t); k < g.childEnd(t); k++) {
                children.add(g.name(g.childAt(k)));
            }
            System.out.printf("  %-6s parents=%-14s children=%s%n", g.name(t), parents, children);
        }

        System.out.println("\nentry tasks (no parents), exit tasks (no children)");
        StringJoiner entry = new StringJoiner(", ");
        StringJoiner exit = new StringJoiner(", ");
        for (int t = 0; t < g.taskCount(); t++) {
            if (g.parentCount(t) == 0) {
                entry.add(g.name(t));
            }
            if (g.childCount(t) == 0) {
                exit.add(g.name(t));
            }
        }
        System.out.println("  entry: " + entry);
        System.out.println("  exit:  " + exit);

        StringJoiner order = new StringJoiner(" -> ");
        for (int t : g.topologicalOrder()) {
            order.add(g.name(t));
        }
        System.out.println("\ntopological order\n  " + order);

        System.out.println("\nbottom levels (longest path to an exit — the B&B lower bound)");
        int[] bLevel = bottomLevels(g);
        for (int t = 0; t < g.taskCount(); t++) {
            System.out.printf("  %-6s %d%n", g.name(t), bLevel[t]);
        }

        System.out.println("\nraw CSR layout");
        System.out.println("  childOffset  = " + offsets(g, true));
        System.out.println("  childTargets = " + targets(g, true));
        System.out.println("  parentOffset = " + offsets(g, false));
        System.out.println("  parentTargets= " + targets(g, false));
        System.out.println();
    }

    /** Longest path from each task to any exit task, computed in reverse topological order. */
    private static int[] bottomLevels(TaskGraph g) {
        int[] bLevel = new int[g.taskCount()];
        int[] order = g.topologicalOrder();
        for (int i = order.length - 1; i >= 0; i--) {
            int t = order[i];
            int best = 0;
            for (int k = g.childStart(t); k < g.childEnd(t); k++) {
                best = Math.max(best, bLevel[g.childAt(k)]);
            }
            bLevel[t] = g.weight(t) + best;
        }
        return bLevel;
    }

    private static String offsets(TaskGraph g, boolean children) {
        StringJoiner out = new StringJoiner(", ", "[", "]");
        for (int t = 0; t < g.taskCount(); t++) {
            out.add(Integer.toString(children ? g.childStart(t) : g.parentStart(t)));
        }
        int last = g.taskCount() - 1;
        out.add(Integer.toString(children ? g.childEnd(last) : g.parentEnd(last)));
        return out.toString();
    }

    private static String targets(TaskGraph g, boolean children) {
        StringJoiner out = new StringJoiner(", ", "[", "]");
        for (int t = 0; t < g.taskCount(); t++) {
            int start = children ? g.childStart(t) : g.parentStart(t);
            int end = children ? g.childEnd(t) : g.parentEnd(t);
            for (int k = start; k < end; k++) {
                out.add(Integer.toString(children ? g.childAt(k) : g.parentAt(k)));
            }
        }
        return out.toString();
    }
}