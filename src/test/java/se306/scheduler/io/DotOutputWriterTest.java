package se306.scheduler.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se306.scheduler.graph.GraphBuilder;
import se306.scheduler.graph.TaskGraph;
import se306.scheduler.schedule.ListScheduler;
import se306.scheduler.schedule.Schedule;

/**
 * Tests the DOT output writer (WBS 2.4).
 *
 * <p>Most tests build the {@link Schedule} by hand instead of running a scheduler over the graph: the
 * writer's job is to render whatever assignment it is handed, so pinning the expected text to a
 * hand-made schedule keeps these tests from failing when the search that produces real ones is
 * swapped for the branch-and-bound one.
 *
 * <p>The graph used throughout is the Figure 1 diamond, as in the input parser's tests:
 *
 * <pre>
 *        A(2)
 *       /    \
 *     3/      \3
 *     B(3)    C(6)
 *       \    /
 *       2\  /8
 *        D(9)
 * </pre>
 */
class DotOutputWriterTest {

    private final DotOutputWriter writer = new DotOutputWriter();

    /** The diamond above, declared in the order A, B, C, D so task indices are 0, 1, 2, 3. */
    private static TaskGraph diamond() {
        GraphBuilder builder = new GraphBuilder();
        builder.graphName("example");
        builder.addNode("A", 2);
        builder.addNode("B", 3);
        builder.addNode("C", 6);
        builder.addNode("D", 9);
        builder.addEdge("A", "B", 3);
        builder.addEdge("A", "C", 3);
        builder.addEdge("B", "D", 2);
        builder.addEdge("C", "D", 8);
        return builder.build();
    }

    @Test
    @DisplayName("renders tasks with Weight, Start and Processor, then the weighted dependencies")
    void rendersScheduledGraph() {
        TaskGraph graph = diamond();
        // A and B on processor 0, C and D on processor 1 — not optimal, just a valid assignment.
        Schedule schedule = new Schedule(graph,
                new int[] { 0, 2, 5, 13 },
                new int[] { 0, 0, 1, 1 },
                2);

        assertEquals("""
                digraph "example" {
                \tA\t [Weight=2,Start=0,Processor=1];
                \tB\t [Weight=3,Start=2,Processor=1];
                \tC\t [Weight=6,Start=5,Processor=2];
                \tD\t [Weight=9,Start=13,Processor=2];
                \tA -> B\t [Weight=3];
                \tA -> C\t [Weight=3];
                \tB -> D\t [Weight=2];
                \tC -> D\t [Weight=8];
                }
                """, writer.toDot(graph, schedule));
    }

    @Test
    @DisplayName("numbers processors from 1, so a P-processor schedule uses 1..P")
    void processorNumbersAreOneBased() {
        TaskGraph graph = diamond();
        Schedule schedule = new Schedule(graph,
                new int[] { 0, 2, 2, 11 },
                new int[] { 0, 0, 1, 2 },
                3);

        String dot = writer.toDot(graph, schedule);
        assertTrue(dot.contains("A\t [Weight=2,Start=0,Processor=1];"), dot);
        assertTrue(dot.contains("C\t [Weight=6,Start=2,Processor=2];"), dot);
        assertTrue(dot.contains("D\t [Weight=9,Start=11,Processor=3];"), dot);
        assertFalse(dot.contains("Processor=0"), "processors must be numbered from 1, not 0");
    }

    @Test
    @DisplayName("output re-parses into the graph it came from, with Start and Processor ignored")
    void outputIsValidInputForTheParser() {
        TaskGraph graph = diamond();
        Schedule schedule = new ListScheduler(graph, 2).solve();

        TaskGraph reparsed = new DotParser().parse(writer.toDot(graph, schedule));

        assertEquals(graph.graphName(), reparsed.graphName());
        assertEquals(graph.taskCount(), reparsed.taskCount());
        assertEquals(graph.edgeCount(), reparsed.edgeCount());
        for (int t = 0; t < graph.taskCount(); t++) {
            int same = reparsed.indexOf(graph.name(t));
            assertTrue(same >= 0, "task '" + graph.name(t) + "' missing from the output");
            assertEquals(graph.weight(t), reparsed.weight(same),
                    "weight of task '" + graph.name(t) + "'");
            for (int k = graph.childStart(t); k < graph.childEnd(t); k++) {
                int child = graph.childAt(k);
                int sameChild = reparsed.indexOf(graph.name(child));
                assertTrue(reparsed.hasEdge(same, sameChild),
                        "dependency '" + graph.name(t) + " -> " + graph.name(child) + "' missing");
                assertEquals(graph.commCost(t, child), reparsed.commCost(same, sameChild),
                        "cost of '" + graph.name(t) + " -> " + graph.name(child) + "'");
            }
        }
    }

    @Test
    @DisplayName("quotes names that are not plain identifiers, and escapes quotes inside them")
    void quotesNamesThatNeedIt() {
        GraphBuilder builder = new GraphBuilder();
        builder.graphName("odd names");
        builder.addNode("plain_1", 1);
        builder.addNode("two words", 2);
        builder.addNode("node", 3);          // a DOT keyword
        builder.addNode("1st", 4);           // leading digit
        builder.addNode("say \"hi\"", 5);    // embedded quotes
        builder.addEdge("two words", "node", 6);
        TaskGraph graph = builder.build();
        Schedule schedule = new Schedule(graph, new int[5], new int[5], 1);

        assertEquals("""
                digraph "odd names" {
                \tplain_1\t [Weight=1,Start=0,Processor=1];
                \t"two words"\t [Weight=2,Start=0,Processor=1];
                \t"node"\t [Weight=3,Start=0,Processor=1];
                \t"1st"\t [Weight=4,Start=0,Processor=1];
                \t"say \\"hi\\""\t [Weight=5,Start=0,Processor=1];
                \t"two words" -> "node"\t [Weight=6];
                }
                """, writer.toDot(graph, schedule));
    }

    @Test
    @DisplayName("a quoted name survives a round trip through the parser")
    void quotedNamesRoundTrip() {
        GraphBuilder builder = new GraphBuilder();
        builder.graphName("quoted");
        builder.addNode("two words", 2);
        builder.addNode("node", 3);
        builder.addEdge("two words", "node", 6);
        TaskGraph graph = builder.build();

        TaskGraph reparsed = new DotParser()
                .parse(writer.toDot(graph, new Schedule(graph, new int[2], new int[2], 1)));

        assertEquals(2, reparsed.taskCount());
        assertTrue(reparsed.indexOf("two words") >= 0, "quoted name lost its spaces");
        assertTrue(reparsed.hasEdge(reparsed.indexOf("two words"), reparsed.indexOf("node")),
                "dependency between quoted names lost");
    }

    @Test
    @DisplayName("a graph with no tasks writes an empty digraph")
    void writesEmptyGraph() {
        TaskGraph graph = new GraphBuilder().graphName("empty").build();
        Schedule schedule = new Schedule(graph, new int[0], new int[0], 1);

        assertEquals("digraph \"empty\" {\n}\n", writer.toDot(graph, schedule));
    }

    @Test
    @DisplayName("write() creates the file, including a missing parent directory")
    void writesFileToDisk(@TempDir Path tempDir) throws IOException {
        TaskGraph graph = diamond();
        Schedule schedule = new ListScheduler(graph, 2).solve();
        Path output = tempDir.resolve("nested").resolve("example-output.dot");

        writer.write(graph, schedule, output);

        assertTrue(Files.exists(output), "output file was not created at " + output);
        assertEquals(writer.toDot(graph, schedule),
                Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("write() replaces an existing file rather than appending to it")
    void overwritesExistingFile(@TempDir Path tempDir) throws IOException {
        TaskGraph graph = diamond();
        Schedule schedule = new ListScheduler(graph, 2).solve();
        Path output = tempDir.resolve("example-output.dot");
        Files.writeString(output, "stale contents from an earlier run that must not survive");

        writer.write(graph, schedule, output);

        assertEquals(writer.toDot(graph, schedule),
                Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a schedule for a different graph is rejected rather than written")
    void rejectsScheduleOfTheWrongSize() {
        TaskGraph graph = diamond();
        GraphBuilder smaller = new GraphBuilder();
        smaller.addNode("A", 2);
        TaskGraph other = smaller.build();
        Schedule scheduleForOther = new Schedule(other, new int[1], new int[1], 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> writer.toDot(graph, scheduleForOther));
        assertTrue(thrown.getMessage().contains("1 tasks"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("4"), thrown.getMessage());
    }
}