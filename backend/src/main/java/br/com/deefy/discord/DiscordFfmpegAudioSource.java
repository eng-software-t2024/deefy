package br.com.deefy.discord;

import net.dv8tion.jda.api.audio.AudioSendHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiscordFfmpegAudioSource implements DiscordAudioSource {

    static final int PCM_FRAME_SIZE = (int) (AudioSendHandler.INPUT_FORMAT.getSampleRate() / 50)
            * AudioSendHandler.INPUT_FORMAT.getFrameSize();

    private final Process process;
    private final InputStream pcmStream;
    private final Runnable onFirstFrame;
    private final Runnable onTerminated;
    private final BlockingQueue<byte[]> frameBuffer = new ArrayBlockingQueue<>(50);
    private final AtomicBoolean firstFrameDelivered = new AtomicBoolean();
    private final AtomicBoolean terminationNotified = new AtomicBoolean();
    private final AtomicBoolean processClosed = new AtomicBoolean();
    private final Thread producerThread;

    private volatile boolean sourceEnded;
    private volatile boolean closed;

    public DiscordFfmpegAudioSource(
            Process process,
            Runnable onFirstFrame,
            Runnable onTerminated
    ) {
        this.process = process;
        this.pcmStream = process.getInputStream();
        this.onFirstFrame = onFirstFrame;
        this.onTerminated = onTerminated;
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
            closeResources(true);
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
                closeResources(true);
            }
            return null;
        }
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
        closeResources(false);
    }

    private void produceFrames() {
        try {
            while (!closed) {
                byte[] frame = readFrame();
                if (frame == null) {
                    sourceEnded = true;
                    closeProcess();
                    return;
                }
                frameBuffer.put(frame);
            }
        } catch (IOException exception) {
            sourceEnded = true;
            closeProcess();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            closeProcess();
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
            offset += read;
        }
        return frame;
    }

    private synchronized void closeResources(boolean notifyTermination) {
        if (!closed) {
            closed = true;
            frameBuffer.clear();
            producerThread.interrupt();
            closeProcess();
        }

        if (notifyTermination && terminationNotified.compareAndSet(false, true)) {
            runSafely(onTerminated);
        }
    }

    private synchronized void closeProcess() {
        if (!processClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            pcmStream.close();
        } catch (IOException ignored) {
            // Closing is best effort and must not leak source details into logs.
        }
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
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
