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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
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
    private final AtomicLong emptyPollsWhilePlaying = new AtomicLong();
    private final AtomicLong productionReadNanos = new AtomicLong();
    private final AtomicLong productionReadOperations = new AtomicLong();
    private final AtomicLong deliveryIntervalNanos = new AtomicLong();
    private final AtomicLong deliveryIntervals = new AtomicLong();
    private final AtomicLong lastDeliveryNanos = new AtomicLong();
    private final AtomicLong minimumDeliveryIntervalNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maximumDeliveryIntervalNanos = new AtomicLong();
    private final AtomicInteger minimumBufferDepth = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger maximumBufferDepth = new AtomicInteger();
    private final AtomicLong pauseCount = new AtomicLong();
    private final ReentrantLock playbackLock = new ReentrantLock();
    private final Condition playbackResumed = playbackLock.newCondition();
    private final Thread stderrThread;
    private final Thread producerThread;

    private volatile boolean sourceEnded;
    private volatile boolean closed;
    private volatile boolean paused;
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
        if (closed || paused) {
            return false;
        }
        if (!frameBuffer.isEmpty()) {
            observeBufferDepth();
            return true;
        }
        if (sourceEnded) {
            closeResources(true, "natural-end");
        } else if (firstFrameDelivered.get()) {
            emptyPollsWhilePlaying.incrementAndGet();
        }
        return false;
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        if (closed || paused) {
            return null;
        }

        byte[] frame = frameBuffer.poll();
        if (frame == null) {
            if (sourceEnded) {
                closeResources(true, "natural-end");
            } else if (firstFrameDelivered.get()) {
                emptyPollsWhilePlaying.incrementAndGet();
            }
            return null;
        }
        framesDelivered.incrementAndGet();
        observeDeliveryInterval();
        observeBufferDepth();
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
    public void pause() {
        playbackLock.lock();
        try {
            if (!closed) {
                if (!paused) {
                    pauseCount.incrementAndGet();
                    paused = true;
                }
            }
        } finally {
            playbackLock.unlock();
        }
    }

    @Override
    public void resume() {
        playbackLock.lock();
        try {
            paused = false;
            playbackResumed.signalAll();
        } finally {
            playbackLock.unlock();
        }
    }

    @Override
    public boolean isPaused() {
        return paused;
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

    long emptyPollsWhilePlaying() {
        return emptyPollsWhilePlaying.get();
    }

    int bufferDepthMin() {
        return minimumBufferDepth.get() == Integer.MAX_VALUE ? 0 : minimumBufferDepth.get();
    }

    int bufferDepthMax() {
        return maximumBufferDepth.get();
    }

    long deliveryIntervals() {
        return deliveryIntervals.get();
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
                awaitPlayback();
                if (closed) {
                    return;
                }
                byte[] frame = readFrame();
                if (frame == null) {
                    return;
                }
                framesProduced.incrementAndGet();
                frameBuffer.put(frame);
                observeBufferDepth();
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
        long started = System.nanoTime();
        productionReadOperations.incrementAndGet();
        byte[] frame = new byte[PCM_FRAME_SIZE];
        try {
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
        } finally {
            productionReadNanos.addAndGet(System.nanoTime() - started);
        }
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
            resume();
            producerThread.interrupt();
            closeProcess();
        }
        joinQuietly(stderrThread);
        logSummary(reason);

        if (notifyTermination && terminationNotified.compareAndSet(false, true)) {
            runSafely(onTerminated);
        }
    }

    private void awaitPlayback() throws InterruptedException {
        playbackLock.lockInterruptibly();
        try {
            while (paused && !closed) {
                playbackResumed.await();
            }
        } finally {
            playbackLock.unlock();
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
                "FFmpeg finished: trackId={}, reason={}, exitCode={}, pcmBytes={}, framesProduced={}, "
                        + "framesDelivered={}, emptyPollsWhilePlaying={}, bufferDepthMin={}, bufferDepthMax={}, "
                        + "deliveryIntervalAvgMs={}, deliveryIntervalMinMs={}, deliveryIntervalMaxMs={}, "
                        + "productionReadAvgMs={}, pauseCount={}, sourceEnded={}, stderr={}",
                trackId,
                reason,
                exitCode == null ? "unknown" : exitCode,
                pcmBytesProduced.get(),
                framesProduced.get(),
                framesDelivered.get(),
                emptyPollsWhilePlaying.get(),
                minimumBufferDepth.get() == Integer.MAX_VALUE ? 0 : minimumBufferDepth.get(),
                maximumBufferDepth.get(),
                averageDeliveryIntervalMillis(),
                nanosToMillis(minimumDeliveryIntervalNanos.get() == Long.MAX_VALUE
                        ? 0
                        : minimumDeliveryIntervalNanos.get()),
                nanosToMillis(maximumDeliveryIntervalNanos.get()),
                averageProductionReadMillis(),
                pauseCount.get(),
                sourceEnded,
                stderrSummary.isBlank() ? "none" : stderrSummary
        );
    }

    private void observeBufferDepth() {
        int depth = frameBuffer.size();
        minimumBufferDepth.accumulateAndGet(depth, Math::min);
        maximumBufferDepth.accumulateAndGet(depth, Math::max);
    }

    private void observeDeliveryInterval() {
        long now = System.nanoTime();
        long previous = lastDeliveryNanos.getAndSet(now);
        if (previous <= 0) {
            return;
        }
        long interval = now - previous;
        deliveryIntervalNanos.addAndGet(interval);
        deliveryIntervals.incrementAndGet();
        minimumDeliveryIntervalNanos.accumulateAndGet(interval, Math::min);
        maximumDeliveryIntervalNanos.accumulateAndGet(interval, Math::max);
    }

    private long averageDeliveryIntervalMillis() {
        long intervals = deliveryIntervals.get();
        return intervals == 0 ? 0 : nanosToMillis(deliveryIntervalNanos.get() / intervals);
    }

    private long averageProductionReadMillis() {
        long operations = productionReadOperations.get();
        return operations == 0 ? 0 : nanosToMillis(productionReadNanos.get() / operations);
    }

    private long nanosToMillis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
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
