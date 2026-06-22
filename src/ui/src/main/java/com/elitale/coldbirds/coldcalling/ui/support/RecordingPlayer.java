package com.elitale.coldbirds.coldcalling.ui.support;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal single-clip WAV player with play / pause / resume, backed by
 * {@link javax.sound.sampled.Clip}. Plays one recording at a time — starting a
 * new file stops the previous one.
 *
 * <p>Designed for the call-recordings detail dialog. All public methods are
 * synchronized; the {@code onChange} callback fires whenever playback state
 * changes (including natural end-of-stream from the audio thread), so the UI
 * must marshal it onto the FX Application Thread itself.
 */
public final class RecordingPlayer implements AutoCloseable {

    private Clip clip;
    private Path current;
    private Runnable onChange = () -> {};

    /** Register a callback fired on every state change. Must not be null. */
    public synchronized void setOnChange(final Runnable callback) {
        this.onChange = Objects.requireNonNull(callback, "callback must not be null");
    }

    /** The file currently loaded into the player, if any. */
    public synchronized Optional<Path> current() {
        return Optional.ofNullable(current);
    }

    /** True when {@code file} is the loaded clip and it is actively playing. */
    public synchronized boolean isPlaying(final Path file) {
        return clip != null && current != null && current.equals(file) && clip.isRunning();
    }

    /**
     * Toggle playback for {@code file}: start it if not loaded, pause if playing,
     * resume (or restart from the beginning if finished) if paused.
     *
     * @param file the WAV recording to play; must not be null
     */
    public synchronized void toggle(final Path file) {
        Objects.requireNonNull(file, "file must not be null");

        if (clip != null && file.equals(current)) {
            if (clip.isRunning()) {
                clip.stop();                       // pause, keep position
            } else {
                if (clip.getFramePosition() >= clip.getFrameLength()) {
                    clip.setFramePosition(0);      // restart after natural end
                }
                clip.start();                      // resume
            }
            onChange.run();
            return;
        }

        load(file);
    }

    /** Stop and release any loaded clip. */
    public synchronized void stop() {
        dispose();
        onChange.run();
    }

    @Override
    public synchronized void close() {
        dispose();
    }

    private void load(final Path file) {
        dispose();
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            final Clip c = AudioSystem.getClip();
            c.open(in);
            c.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    onChange.run();
                }
            });
            clip = c;
            current = file;
            c.start();
            onChange.run();
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
            dispose();
            onChange.run();
        }
    }

    private void dispose() {
        if (clip != null) {
            clip.stop();
            clip.close();
            clip = null;
            current = null;
        }
    }
}
