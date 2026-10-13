package br.com.deefy.discord;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordFfmpegAudioSourceTest {

    @Test
    void shouldProvidePcmAndNotifyNaturalTerminationExactlyOnce() {
        byte[] pcm = new byte[DiscordFfmpegAudioSource.PCM_FRAME_SIZE];
        pcm[0] = 42;
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(pcm));
        when(process.isAlive()).thenReturn(true);
        AtomicInteger firstFrames = new AtomicInteger();
        AtomicInteger terminations = new AtomicInteger();
        DiscordFfmpegAudioSource source = new DiscordFfmpegAudioSource(
                process,
                firstFrames::incrementAndGet,
                terminations::incrementAndGet
        );

        assertThat(waitUntil(source::canProvide)).isTrue();
        ByteBuffer frame = source.provide20MsAudio();
        assertThat(frame).isNotNull();
        assertThat(frame.remaining()).isEqualTo(DiscordFfmpegAudioSource.PCM_FRAME_SIZE);
        assertThat(frame.get()).isEqualTo((byte) 42);
        assertThat(firstFrames).hasValue(1);

        assertThat(waitUntil(() -> !source.canProvide() && terminations.get() == 1)).isTrue();
        source.canProvide();
        source.close();

        assertThat(terminations).hasValue(1);
        verify(process, times(1)).destroy();
        verify(process, times(1)).destroyForcibly();
    }

    @Test
    void shouldCloseIdempotentlyWithoutPublishingFalseNaturalTermination() {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        AtomicInteger terminations = new AtomicInteger();
        DiscordFfmpegAudioSource source = new DiscordFfmpegAudioSource(
                process,
                () -> { },
                terminations::incrementAndGet
        );

        source.close();
        source.close();

        assertThat(terminations).hasValue(0);
        verify(process, times(1)).destroy();
    }

    private boolean waitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
        return false;
    }
}
