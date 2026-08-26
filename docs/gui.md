# Visualisation

Package `se306.scheduler.gui`: the JavaFX window behind `-v`. Source:
[`JavaFXLauncher`](../src/main/java/se306/scheduler/gui/JavaFXLauncher.java),
[`MainWindow`](../src/main/java/se306/scheduler/gui/MainWindow.java),
[`MainWindowController`](../src/main/java/se306/scheduler/gui/MainWindowController.java)
(with the `MainWindow.fxml` layout and `main-window.css` in `src/main/resources`),
[`GanttChartPanel`](../src/main/java/se306/scheduler/gui/GanttChartPanel.java),
[`SearchTreePanel`](../src/main/java/se306/scheduler/gui/SearchTreePanel.java),
[`MetricsPanel`](../src/main/java/se306/scheduler/gui/MetricsPanel.java),
[`MetricsHistory`](../src/main/java/se306/scheduler/gui/MetricsHistory.java),
[`PlaybackController`](../src/main/java/se306/scheduler/gui/PlaybackController.java),
[`ConvergenceChartView`](../src/main/java/se306/scheduler/gui/ConvergenceChartView.java),
[`SearchListener`](../src/main/java/se306/scheduler/gui/SearchListener.java); plus
[`SearchMetrics`](../src/main/java/se306/scheduler/algorithm/metrics/SearchMetrics.java) on the
algorithm side.

## Launch and lifecycle

`Main` opens the window only when `-v` is given, and only *after* the input has parsed, so a bad
input file never flashes a window. `JavaFXLauncher.launchAndGetWindow` starts the JavaFX
application on its own thread and blocks until `start()` has loaded `MainWindow.fxml`, built the
scene (fixed at 1280x720) and assembled a `MainWindow` from the controller's three panels; it then
returns that `MainWindow` to `Main`.

`Main` wires it in twice: the window is passed to the algorithm as its `SearchListener`, and
`startMonitoring(context)` is called before `solve()` so sampling begins with the search. When
`solve()` returns, `stopMonitoring()` takes one final sample and switches the panel into replay;
the pipeline then writes the output file as usual, and the window stays open for inspection.

Closing the window calls `Platform.exit()` and `System.exit(0)`. That is deliberate: it also
stops a search that is still running, so the process never outlives the window.

## Where the data comes from

Two channels feed the GUI, and keeping them separate is the core of the design. The search records
no history and takes no readings itself; a headless run pays for nothing.

| Channel          | Carries                                    | Pushed or pulled                                           | How often              |
| ---------------- | ------------------------------------------ | ---------------------------------------------------------- | ---------------------- |
| **Improvements** | a new best schedule                        | pushed by the search, `SearchListener.onNewBestSchedule`, from a worker thread | whenever one is found (sparse) |
| **Frames**       | branch counts, memory, CPU, elapsed time   | pulled by a timer in `MainWindow` calling `SearchMetrics.snapshot()` on the FX thread | every 0.1 s (`MetricsPanel.SAMPLE_INTERVAL`) |

An improvement is the only event the algorithm ever pushes. `MainWindow.onNewBestSchedule` reads
the elapsed time immediately on the worker thread, then hops to the FX thread with
`Platform.runLater` to redraw the Gantt chart and add a step to the convergence staircase. Reading
the time before the hop matters: a busy FX thread can delay when an improvement is *drawn*, but
never distorts when it is recorded as having *happened*.

Everything else, branch counters included, is sampled by the timer. The expensive readings
(process CPU load, heap usage) happen only inside `snapshot()`, so they run ten times a second on
the FX thread and never on a worker or inside a lock. The counters themselves are batched on the
search side so sampling stays accurate without slowing the search; see
[algorithm.md](algorithm.md#counting-work-searchmetrics).

## The window

Three buttons switch a single viewport between the Gantt chart (the default), the search tree
(currently a placeholder panel), and the metrics panel. For the chart views,
`MainWindowController` implements pan and zoom:

- Drag with the left button to pan. Panning is clamped so the content can never be dragged fully
  out of view; content smaller than the viewport stays centred.
- Scroll (or pinch) to zoom, anchored so the point under the cursor stays put. Scale is clamped
  between "fits the viewport" and 4x.
- Auto-fit keeps the chart fitted and centred, including when a new best schedule changes its
  size, until the user zooms in past fit scale; switching panels turns auto-fit back on.
- The viewport is clipped, and the chart panels are excluded from parent layout
  (`setManaged(false)`), so a very large schedule grows the canvas, never the window.

## The Gantt chart

`GanttChartPanel` draws onto a canvas, re-rendered each time the search pushes a new best
schedule, so at any moment it shows the best schedule found so far and it ends on the optimal
one. One row per processor, labelled `P1..Pn` to match the 1-based numbering in the output file;
the x axis is time, with gridlines and labels every 2 units. Each task is a rectangle at
(start time, processor row) whose width is its weight, labelled with the task's name. The canvas
resizes itself to `makespan * 30` pixels wide plus margins, and the controller's fit logic scales
it into view.

## The metrics panel

The panel has a header row (timeline label, a Running/Complete status pill, Play and speed
buttons), a time slider, five stat tiles (branches explored, current best makespan, time taken,
memory usage, CPU usage), a "search space pruned" meter showing pruned against explored branch
counts, and the convergence chart in the centre.

It has two modes:

- **Live**, while the search runs. The view pins itself to the newest frame as samples arrive and
  the slider is locked. An improvement that lands between samples is drawn immediately rather
  than waiting for the next tick.
- **Replay**, after `markComplete()`. The slider becomes scrubbable across every recorded frame,
  and Play steps through them at real-time pacing (one sampled interval per frame at 1x), with
  the speed button cycling 1x, 4x, 16x, taking effect mid-playback. `PlaybackController` owns
  this; a long run is unwatchable at true real time, hence the speeds.

`MetricsHistory` is the memory behind both modes: the list of sampled `Frame`s and the list of
`Improvement` events, kept as two separate streams for the reasons above. Two guards keep it
honest. Improvements are accepted only if they strictly beat the best seen so far, because
parallel workers notify outside the best-schedule lock and two announcements can arrive in the
opposite order to the one in which they took effect (see [parallel.md](parallel.md#the-shared-best));
a stale one must not be drawn as a step upwards. And improvement times are clamped to be
non-decreasing for the same reason. Past 3600 frames (six minutes at the 0.1 s interval) the
history halves itself, keeping every second frame and doubling the effective interval, so an
hours-long run degrades to coarser sampling instead of growing without bound; playback pacing is
derived from the effective interval, so replay stays real time afterwards.

## The convergence chart

`ConvergenceChartView` plots best-makespan-so-far against time as a staircase. Each improvement
contributes a horizontal segment (the previous best held until that moment) and a drop to the new
value, with a dot on each improvement event; the last value extends flat to the present, because
"still the best we have" is what is actually true, and a marker caps the leading edge. The line
is flat between improvements because nothing happened between them; improvements are never drawn
as if they were samples.

Until the first improvement there is no staircase to draw. That is the normal state on a single
processor, where the greedy seed is usually already optimal, so the chart falls back to a genuine
sampled series of branches explored over time (strided down to at most 400 drawn points; the
underlying history keeps full resolution). With fewer than two frames it shows a "not enough
data" label instead.

Hovering maps the cursor's x position to the nearest sampled frame (binary search over frame
times), stands a crosshair at it, drops a dot onto whichever line is showing, and hands that
frame to the panel so the tiles read out the values actually sampled at that moment; nothing is
interpolated. Hovering past the leading edge clears instead of snapping to the last frame. A live
refresh never steals the readout from under a parked cursor, and if the history decimates itself
the hover is dropped, because every frame index just changed meaning.

The time axis only ever grows: it holds still until the line reaches its right edge, then jumps
out to 1.2x the current time. Between jumps the plot stays put instead of sliding left on every
sample, and scrubbing a finished run back to the start keeps the full axis rather than
re-stretching as the slider moves.

## Threading rules

- Anything that touches a JavaFX node runs on the FX thread; the entry points from other threads
  (`onNewBestSchedule`, `startMonitoring`, `stopMonitoring`) all go through `Platform.runLater`.
- Worker threads never read CPU or heap and never wait on the GUI; the listener is invoked
  outside the best-schedule lock precisely so a slow paint cannot stall the search.
- `MetricsPanel.recordImprovement` and `recordFrame` must be called on the FX thread, which
  `MainWindow` guarantees.

## Extending it

`SearchTreePanel` is a placeholder awaiting a real search-tree visualisation, and
`MainWindow.onNewBestSchedule` has the marked spot where it would be driven from. When adding to
the GUI, keep the two-channel rule: the search pushes only genuine events (a new best), and
anything continuous is sampled by the timer on the FX thread. There are no automated tests for
the panels themselves; the listener path from the algorithm's side is covered by
[`AlgorithmListenerTest`](../src/test/java/se306/scheduler/algorithm/AlgorithmListenerTest.java).
