# Command-line interface

Package `se306.scheduler.cli` (WBS 2.1). Source:
[`CliArguments`](../src/main/java/se306/scheduler/cli/CliArguments.java),
[`CliArgumentException`](../src/main/java/se306/scheduler/cli/CliArgumentException.java);
wired into the pipeline by [`Main`](../src/main/java/se306/scheduler/Main.java).

## Usage

```
java -Xmx4G -jar scheduler.jar INPUT.dot P [-p N] [-v] [-o OUTPUT]
```

| Argument    | Meaning                                                            | Constraint            |
| ----------- | ------------------------------------------------------------------ | --------------------- |
| `INPUT.dot` | Task graph to schedule, in DOT. See [DOT input](dot-input.md)      | Must exist            |
| `P`         | Number of processors to schedule onto                              | Integer, at least 1   |

| Option      | Meaning                                                            | Default                          |
| ----------- | ------------------------------------------------------------------ | -------------------------------- |
| `-p N`      | Run the search on `N` threads (`ParallelAlgorithm`, a `ForkJoinPool` of size `N`; see [parallel.md](parallel.md)) | `1`, the sequential search |
| `-v`        | Open the JavaFX window and show the search as it runs (see [gui.md](gui.md)) | Off                    |
| `-o OUTPUT` | File to write the schedule to                                      | `INPUT-output.dot` beside the input |

Rules the parser applies:

- The two positional arguments come **first**; options follow in any order.
  `-v INPUT.dot 2` is rejected (`P` is read as `INPUT.dot`).
- A repeated option takes its **last** value: `-o first -o second` writes `second.dot`.
- `N` (search threads) and `P` (processors in the schedule) are independent. `N` larger than the
  machine's core count is allowed.
- The jar sets no JVM options itself. The project specification fixes the heap at 4 GB, hence
  `-Xmx4G` in the usage line; run with it so timings match the marking environment.

## Output file name

`-o` and the default are both resolved in `CliArguments`, before anything runs:

| Given                          | Written to                     | Why                                              |
| ------------------------------ | ------------------------------ | ------------------------------------------------ |
| *(no `-o`)*, input `graphs/g.dot` | `graphs/g-output.dot`       | Default keeps the input's directory              |
| *(no `-o`)*, input `g.DOT`     | `g-output.dot`                 | The `.dot` suffix is stripped case-insensitively |
| *(no `-o`)*, input `g`         | `g-output.dot`                 | `-output.dot` is appended to the whole name      |
| `-o result`                    | `result.dot`                   | Output is always DOT, so the extension is added  |
| `-o result.dot` / `-o result.DOT` | unchanged                   | An existing `.dot` suffix, any case, is kept     |
| `-o out/nested/result.dot`     | `out/nested/result.dot`        | Directories are kept and created if missing      |

The file is always written and always replaces an existing file of the same name.

## Exit status

| Exit | When                                                                        | Where it's reported |
| ---- | --------------------------------------------------------------------------- | ------------------- |
| `0`  | Schedule written. Stdout shows the solve time, then the summary (see below) | stdout              |
| `1`  | Input can't be read, isn't a valid task graph, or output can't be written   | stderr, `Error: …`  |
| `2`  | Command line doesn't match the usage                                        | stderr, `Error: …`  |

A successful run prints exactly two lines to stdout: the solve time as soon as the search
finishes, and the summary once the file is written.

```
Schedule generated in 0.012 seconds
Schedule[4 tasks, 2 processors, makespan=19] written to example-output.dot
```

Every exit-2 message is produced by `CliArguments.parse`:

| Command line                    | Message                                                    |
| ------------------------------- | ---------------------------------------------------------- |
| *(nothing)* or `INPUT.dot` only | `expected an input file and a processor count`             |
| `INPUT.dot 0`                   | `P (number of processors) must be at least 1, got 0`       |
| `INPUT.dot x`                   | `P (number of processors) must be an integer, got 'x'`     |
| `INPUT.dot 2 -p 0`              | `N (number of cores) must be at least 1, got 0`            |
| `INPUT.dot 2 -p`                | `option '-p' requires a value`                             |
| `INPUT.dot 2 -q`                | `unknown option '-q'`                                      |

Exit-1 messages come from the stage that failed: `could not read 'INPUT.dot': …` when the file is
missing, the parser and graph-validation messages listed in [DOT input](dot-input.md#errors), or
`could not write 'OUTPUT': …` from the writer. `Main` prints only the message; there is no usage
text on error.

## How it fits together

`CliArguments` is a record with one static entry point:

```java
public record CliArguments(Path inputFile, int processorCount, int coreCount, boolean visualise,
        Path outputFile) {
    public static CliArguments parse(String[] args) // throws CliArgumentException
}
```

It only captures and validates; it never opens a file or starts a search. `Main` does the
sequencing:

1. `CliArguments.parse(args)`. On `CliArgumentException`, print and exit 2.
2. `new DotParser().parse(inputFile)`. On any parse or validation error, print and exit 1.
3. If `-v`, launch the JavaFX window (`JavaFXLauncher.launchAndGetWindow`) and pass it to the
   algorithm as its `SearchListener`. This happens *after* parsing, so a bad input file never opens
   a window.
4. `coreCount > 1` selects `ParallelAlgorithm`, otherwise `SequentialAlgorithm`; call `solve()`,
   timing it, and print `Schedule generated in %.3f seconds`.
5. `new DotOutputWriter().write(graph, schedule, outputFile)`. On `IOException`, exit 1.
6. Print the schedule summary and the output path.

## Adding an option

1. Add a component to the `CliArguments` record and a default in `parse`.
2. Add a `case` to the `switch` in `parse`; use `optionValue` for flags that take a value and
   `parsePositiveInt` for counts so the error wording stays consistent.
3. Add a case to `CliArgumentsTest`, and update the usage block in the [README](../README.md) and
   the tables above.

## Tests

[`CliArgumentsTest`](../src/test/java/se306/scheduler/cli/CliArgumentsTest.java) covers: required
arguments only, each option alone, all options together in any order, `-o` with and without the
extension, `-o` and the default keeping their directory, missing arguments, non-positive or
non-integer counts, an option missing its value, and an unknown option.
