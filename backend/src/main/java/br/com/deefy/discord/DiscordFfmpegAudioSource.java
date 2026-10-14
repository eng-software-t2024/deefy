package br.com.deefy.discord;

import net.dv8tion.jda.api.audio.AudioSendHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class DiscordFfmpegAudioSource implements DiscordAudioSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordFfmpegAudioSource.class);
    private static final int MAX_STDERR_CHARACTERS = 4096;
    private static final Pattern SENSITIVE_URL = Pattern.compile("(?i)\\b(?:https?|jdbc):\\S+");
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)\\b(authorization|bearer|token|password|passwd|cookie|jwt|api[_-]?key)"
                    + "(?:\\s*[:=]\\s*|\\s+)[^\\s,;]+"
    );

    static final int PCM_FRAME_SIZE = (int) (AudioSendHandler.INPUT_FORMAT.getSampleRate() / 50)
            * AudioSendHandler.INPUT_FORMAT.getFrameSize();

    private final Process process;
    private final long trackId;
    private final InputStream pcmStream;
    private final InputStream stderrStream;
    private final Runnable onFirstFrame;
    private final Runnable onTerminated;
    private final BlockingQueue<byte[]> frameBuffer = new ArrayBlockingQueue<>(50);
    private final AtomicBoolean firstFrameDelivered = new AtomicBoolean();
    private final AtomicBoolean terminationNotified = new AtomicBoolean();
    private final AtomicBoolean processClosed = new AtomicBoolean();
    private final AtomicBoolean summaryLogged = new AtomicBoolean();
    private final AtomicLong pcmBytesProduced = new AtomicLong();
    private final AtomicLong framesProduced = new AtomicLong();
    private final AtomicLong framesDelivered = new AtomicLong();
    private final Thread stderrThread;
    private final Thread producerThread;

    private volatile boolean sourceEnded;
    private volatile boolean closed;
    private volatile Integer exitCode;
    private volatile String stderrSummary = "";

    public DiscordFfmpegAudioSource(
            Process process,
            long trackId,
            Runnable onFirstFrame,
            Runnable onTerminated
    ) {
        this.process = process;
        this.trackId = trackId;
        this.pcmStream = nonNullStream(process.getInputStream());
        this.stderrStream = nonNullStream(process.getErrorStream());
        this.onFirstFrame = onFirstFrame;
        this.onTerminated = onTerminated;
        this.stderrThread = Thread.ofVirtual()
                .name("deefy-discord-ffmpeg-stderr")
                .start(this::captureStderr);
        this.producerThread = Thread.ofVirtual()
                .name("deefy-discord-audio-source")
                .start(this::produceFrames);
    }

    @Override
    public boolean canProvide() {
        if (closed) {
            return false;
        }
        if (!frameBuffer.isEmpty()) {
            return true;
        }
        if (sourceEnded) {
            closeResources(true, "natural-end");
        }
        return false;
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        if (closed) {
            return null;
        }

        byte[] frame = frameBuffer.poll();
        if (frame == null) {
            if (sourceEnded) {
                closeResources(true, "natural-end");
            }
            return null;
        }
        framesDelivered.incrementAndGet();
        if (firstFrameDelivered.compareAndSet(false, true)) {
            runSafely(onFirstFrame);
        }
        return ByteBuffer.wrap(frame);
    }

    @Override
    public boolean isOpus() {
        return false;
    }

    @Override
    public void close() {
        closeResources(false, "session-cleanup");
    }

    long pcmBytesProduced() {
        return pcmBytesProduced.get();
    }

    long framesProduced() {
        return framesProduced.get();
    }

    long framesDelivered() {
        return framesDelivered.get();
    }

    Integer exitCode() {
        return exitCode;
    }

    String sanitizedStderr() {
        return stderrSummary;
    }

    private void produceFrames() {
        try {
            while (!closed) {
                byte[] frame = readFrame();
                if (frame == null) {
                    return;
                }
                framesProduced.incrementAndGet();
                frameBuffer.put(frame);
            }
        } catch (IOException exception) {
            LOGGER.warn("FFmpeg PCM stream failed: trackId={}", trackId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            sourceEnded = true;
            captureExitCode(false);
        }
    }

    private byte[] readFrame() throws IOException {
        byte[] frame = new byte[PCM_FRAME_SIZE];
        int offset = 0;
        while (offset < frame.length) {
            int read = pcmStream.read(frame, offset, frame.length - offset);
            if (read < 0) {
                return null;
            }
            pcmBytesProduced.addAndGet(read);
            offset += read;
        }
        return frame;
    }

    private void captureStderr() {
        byte[] buffer = new byte[512];
        StringBuilder captured = new StringBuilder(MAX_STDERR_CHARACTERS);
        try {
            int read;
            while ((read = stderrStream.read(buffer)) >= 0) {
                if (captured.length() >= MAX_STDERR_CHARACTERS) {
                    continue;
                }
                String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                int remaining = MAX_STDERR_CHARACTERS - captured.length();
                captured.append(chunk, 0, Math.min(chunk.length(), remaining));
            }
        } catch (IOException ignored) {
            // The stream may be closed during an explicit cleanup.
        } finally {
            stderrSummary = sanitizeDiagnostic(captured.toString());
        }
    }

    private void closeResources(boolean notifyTermination, String reason) {
        boolean shouldClose;
        synchronized (this) {
            shouldClose = !closed;
            if (shouldClose) {
                closed = true;
                frameBuffer.clear();
            }
        }

        if (shouldClose) {
            producerThread.interrupt();
            closeProcess();
        }
        joinQuietly(stderrThread);
        logSummary(reason);

        if (notifyTermination && terminationNotified.compareAndSet(false, true)) {
            runSafely(onTerminated);
        }
    }

    private void closeProcess() {
        if (!processClosed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(pcmStream);
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        captureExitCode(true);
        closeQuietly(stderrStream);
    }

    private void captureExitCode(boolean processMayBeRunning) {
        try {
            if (!processMayBeRunning && process.waitFor(2, TimeUnit.SECONDS)) {
                exitCode = process.exitValue();
            } else if (!process.isAlive()) {
                exitCode = process.exitValue();
            }
        } catch (IllegalThreadStateException ignored) {
            // The explicit cleanup path will capture the exit code later.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void logSummary(String reason) {
        if (!summaryLogged.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info(
                "FFmpeg finished: trackId={}, reason={}, exitCode={}, pcmBytes={}, framesProduced={}, framesDelivered={}, stderr={}",
                trackId,
                reason,
                exitCode == null ? "unknown" : exitCode,
                pcmBytesProduced.get(),
                framesProduced.get(),
                framesDelivered.get(),
                stderrSummary.isBlank() ? "none" : stderrSummary
        );
    }

    static String sanitizeDiagnostic(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) {
            return "";
        }
        String withoutUrls = SENSITIVE_URL.matcher(diagnostic).replaceAll("[redacted-url]");
        String withoutSecrets = SENSITIVE_VALUE.matcher(withoutUrls).replaceAll("$1=[redacted]");
        String singleLine = withoutSecrets.replaceAll("[\\r\\n\\t]+", " ").trim();
        return singleLine.substring(0, Math.min(singleLine.length(), MAX_STDERR_CHARACTERS));
    }

    private InputStream nonNullStream(InputStream stream) {
        return stream == null ? InputStream.nullInputStream() : stream;
    }

    private void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Cleanup remains best effort without exposing stream details.
        }
    }

    private void joinQuietly(Thread thread) {
        if (Thread.currentThread() == thread) {
            return;
        }
        try {
            thread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void runSafely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // JDA audio threads must remain isolated from application callbacks.
        }
    }
}
