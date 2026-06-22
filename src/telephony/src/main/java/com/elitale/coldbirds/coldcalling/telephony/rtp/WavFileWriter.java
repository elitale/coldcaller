package com.elitale.coldbirds.coldcalling.telephony.rtp;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Streaming WAV (RIFF/PCM) writer for 16-bit signed mono audio.
 *
 * <p>Writes a 44-byte header with placeholder sizes up front, appends PCM
 * samples (little-endian) as they arrive, and patches the RIFF and {@code data}
 * chunk sizes on {@link #close()}. Suitable for an open-ended call recording
 * whose length is unknown until the call ends.
 *
 * <p>Not thread-safe — call {@link #write} from a single thread.
 */
public final class WavFileWriter implements AutoCloseable {

    private static final int HEADER_BYTES = 44;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 1;

    private final RandomAccessFile file;
    private final int sampleRate;
    private int dataBytes = 0;

    /**
     * Open a new WAV file for writing.
     *
     * @param path       destination file (parent directories must already exist)
     * @param sampleRate samples per second (e.g. 8000)
     * @throws IOException if the file cannot be created
     */
    public WavFileWriter(final Path path, final int sampleRate) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        if (sampleRate < 1) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        this.sampleRate = sampleRate;
        this.file = new RandomAccessFile(path.toFile(), "rw");
        this.file.setLength(0);
        this.file.write(new byte[HEADER_BYTES]); // placeholder, patched on close
    }

    /**
     * Append signed 16-bit PCM samples.
     *
     * @param samples mono PCM samples; ignored if null or empty
     * @throws IOException if the write fails
     */
    public void write(final short[] samples) throws IOException {
        if (samples == null || samples.length == 0) {
            return;
        }
        final byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[i * 2]     = (byte) (samples[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        file.write(bytes);
        dataBytes += bytes.length;
    }

    /** Patch the header sizes and close the file. */
    @Override
    public void close() throws IOException {
        try {
            file.seek(0);
            file.write(buildHeader());
        } finally {
            file.close();
        }
    }

    private byte[] buildHeader() {
        final int byteRate   = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8;
        final int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;
        final byte[] h = new byte[HEADER_BYTES];

        putAscii(h, 0, "RIFF");
        putLe32(h, 4, 36 + dataBytes);   // RIFF chunk size
        putAscii(h, 8, "WAVE");
        putAscii(h, 12, "fmt ");
        putLe32(h, 16, 16);              // fmt chunk size (PCM)
        putLe16(h, 20, 1);               // audio format = PCM
        putLe16(h, 22, CHANNELS);
        putLe32(h, 24, sampleRate);
        putLe32(h, 28, byteRate);
        putLe16(h, 32, blockAlign);
        putLe16(h, 34, BITS_PER_SAMPLE);
        putAscii(h, 36, "data");
        putLe32(h, 40, dataBytes);       // data chunk size
        return h;
    }

    private static void putAscii(final byte[] b, final int off, final String s) {
        for (int i = 0; i < s.length(); i++) {
            b[off + i] = (byte) s.charAt(i);
        }
    }

    private static void putLe16(final byte[] b, final int off, final int v) {
        b[off]     = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void putLe32(final byte[] b, final int off, final int v) {
        b[off]     = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
