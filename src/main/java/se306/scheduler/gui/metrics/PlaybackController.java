package se306.scheduler.gui.metrics;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Button;
import javafx.scene.control.Slider;
import javafx.util.Duration;

/**
 * Drives replay of a finished run by advancing {@code timeSlider} in continuous seconds at the
 * selected speed multiplier.
 */
class PlaybackController {

    private static final int[] PLAYBACK_SPEEDS = {1, 4, 16};
    private static final Duration PLAYBACK_TICK = Duration.millis(16);

    private final Slider timeSlider;
    private final Button playButton;
    private final Button speedButton;
    private Timeline playbackTimeline;
    private int speedIndex;
    private long lastTickNanos;

    PlaybackController(Slider timeSlider, Button playButton, Button speedButton) {
        this.timeSlider = timeSlider;
        this.playButton = playButton;
        this.speedButton = speedButton;

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
        if (timeSlider.getValue() >= timeSlider.getMax() - 1e-9) {
            timeSlider.setValue(0);
        }
        startPlayback();
    }

    private void startPlayback() {
        lastTickNanos = System.nanoTime();
        playbackTimeline = new Timeline(new KeyFrame(PLAYBACK_TICK, event -> {
            long now = System.nanoTime();
            double deltaSeconds = (now - lastTickNanos) / 1_000_000_000.0;
            lastTickNanos = now;

            double next = Math.min(
                    timeSlider.getMax(),
                    timeSlider.getValue() + deltaSeconds * PLAYBACK_SPEEDS[speedIndex]);
            if (next >= timeSlider.getMax() - 1e-9) {
                timeSlider.setValue(timeSlider.getMax());
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
    }
}
