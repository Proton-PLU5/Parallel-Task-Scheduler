package se306.scheduler.gui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Button;
import javafx.scene.control.Slider;
import javafx.util.Duration;

/**
 * Drives replay of a finished run: steps {@code timeSlider} one frame at a time, one sampled
 * interval per frame at 1x. A long run is unwatchable at true real-time, hence the speed control.
 */
class PlaybackController {

    private static final int[] PLAYBACK_SPEEDS = {1, 4, 16};

    private final Slider timeSlider;
    private final Button playButton;
    private final Button speedButton;
    private final MetricsHistory history;

    private Timeline playbackTimeline;
    private int speedIndex;

    PlaybackController(Slider timeSlider, Button playButton, Button speedButton, MetricsHistory history) {
        this.timeSlider = timeSlider;
        this.playButton = playButton;
        this.speedButton = speedButton;
        this.history = history;

        playButton.setDisable(true);
        playButton.setOnAction(e -> togglePlayback());
        speedButton.setDisable(true);
        speedButton.setOnAction(e -> cycleSpeed());
    }

    /** Stops any playback in progress and disables the controls, ready for a fresh run. */
    void reset() {
        stopPlayback();
        playButton.setDisable(true);
        speedButton.setDisable(true);
    }

    /** Enables or disables the controls once a run finishes, depending on whether it can be scrubbed. */
    void setScrubbable(boolean scrubbable) {
        playButton.setDisable(!scrubbable);
        speedButton.setDisable(!scrubbable);
    }

    private void togglePlayback() {
        if (playbackTimeline != null && playbackTimeline.getStatus() == Animation.Status.RUNNING) {
            stopPlayback();
            return;
        }
        if ((int) timeSlider.getValue() >= history.frameCount() - 1) {
            timeSlider.setValue(0);
        }
        startPlayback();
    }

    private void startPlayback() {
        Duration frameDelay = Duration.seconds(history.frameIntervalSeconds() / PLAYBACK_SPEEDS[speedIndex]);
        playbackTimeline = new Timeline(new KeyFrame(frameDelay, event -> {
            int next = (int) timeSlider.getValue() + 1;
            if (next >= history.frameCount()) {
                stopPlayback();
                return;
            }
            timeSlider.setValue(next);
        }));
        playbackTimeline.setCycleCount(Animation.INDEFINITE);
        playbackTimeline.play();
        playButton.setText("Pause");
    }

    private void stopPlayback() {
        if (playbackTimeline != null) {
            playbackTimeline.stop();
        }
        playButton.setText("Play");
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % PLAYBACK_SPEEDS.length;
        speedButton.setText(PLAYBACK_SPEEDS[speedIndex] + "x");

        // Rebuild the timeline so a speed change takes effect mid-playback rather than at the end.
        if (playbackTimeline != null && playbackTimeline.getStatus() == Animation.Status.RUNNING) {
            playbackTimeline.stop();
            startPlayback();
        }
    }
}
