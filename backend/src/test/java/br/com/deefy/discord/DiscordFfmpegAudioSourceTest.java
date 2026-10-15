package br.com.deefy.discord;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordFfmpegAudioSourceTest {

    @Test
    void shouldProvidePcmRecordTelemetryAndNotifyNaturalTerminationExactlyOnce() throws Exception {
        byte[] pcm = new byte[DiscordFfmpegAudioSource.PCM_FRAME_SIZE];
        pcm[0] = 42;
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(pcm));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(2, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        AtomicInteger firstFrames = new AtomicInteger();
        AtomicInteger terminations = new AtomicInteger();
        DiscordFfmpegAudioSource source = new DiscordFfmpegAudioSource(
                process,
                123L,
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

        assertThat(source.pcmBytesProduced()).isEqualTo(DiscordFfmpegAudioSource.PCM_FRAME_SIZE);
        assertThat(source.framesProduced()).isEqualTo(1);
        assertThat(source.framesDelivered()).isEqualTo(1);
        assertThat(source.exitCode()).isZero();
        assertThat(terminations).hasValue(1);
        verify(process, never()).destroyForcibly();
    }

    @Test
    void shouldCloseIdempotentlyWithoutPublishingFalseNaturalTermination() {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.isAlive()).thenReturn(true);
        AtomicInteger terminations = new AtomicInteger();
        DiscordFfmpegAudioSource source = new DiscordFfmpegAudioSource(
                process,
                123L,
                () -> { },
                terminations::incrementAndGet
        );

        source.close();
        source.close();

        assertThat(terminations).hasValue(0);
        verify(process, times(1)).destroy();
        verify(process, times(1)).destroyForcibly();
    }

    @Test
    void shouldSanitizeAndLimitFfmpegStderr() throws Exception {
        byte[] stderr = ("failed https://deefy.example/media/file.mp3?token=secret "
                + "Authorization: Bearer-secret password=hunter2")
                .getBytes(StandardCharsets.UTF_8);
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(stderr));
        when(process.waitFor(2, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(1);
        DiscordFfmpegAudioSource source = new DiscordFfmpegAudioSource(
                process,
                123L,
                () -> { },
                () -> { }
        );

        assertThat(waitUntil(() -> !source.canProvide())).isTrue();
        source.close();

        assertThat(source.sanitizedStderr())
                .contains("[redacted-url]")
                .contains("Authorization=[redacted]")
                .contains("password=[redacted]")
                .doesNotContain("file.mp3")
                .doesNotContain("Bearer-secret")
                .doesNotContain("hunter2");
        assertThat(source.exitCode()).isEqualTo(1);
    }

    @Test
    void shouldPauseWithoutDeliveringOrDiscardingBufferedAudioAndResumeAtSamePosition() throws Exception {
        byte[] pcm = new byte[DiscordFfmpegAudioSource.PCM_FRAME_SIZE * 2];
        pcm[0] = 11;
        pcm[DiscordFfmpegAudioSource.PCM_FRAME_SIZE] = 22;
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(pcm));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(2, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        DiscordFfmpegAudioSource source = new DiscordFfmpegAudioSource(process, 123L, () -> { }, () -> { });

        assertThat(waitUntil(() -> source.framesProduced() == 2)).isTrue();
        source.pause();
        assertThat(source.isPaused()).isTrue();
        assertThat(source.canProvide()).isFalse();
        assertThat(source.provide20MsAudio()).isNull();
        assertThat(source.framesDelivered()).isZero();

        source.resume();
        assertThat(source.isPaused()).isFalse();
        ByteBuffer first = source.provide20MsAudio();
        ByteBuffer second = source.provide20MsAudio();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.get()).isEqualTo((byte) 11);
        assertThat(second.get()).isEqualTo((byte) 22);
        assertThat(source.framesDelivered()).isEqualTo(2);
        source.close();
    }

    @Test
    void shouldCollectAggregateMetricsWithoutChangingFrameDelivery() throws Exception {
        byte[] pcm = new byte[DiscordFfmpegAudioSource.PCM_FRAME_SIZE * 3];
        pcm[0] = 1;
        pcm[DiscordFfmpegAudioSource.PCM_FRAME_SIZE] = 2;
        pcm[DiscordFfmpegAudioSource.PCM_FRAME_SIZE * 2] = 3;
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(pcm));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(2, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        DiscordFfmpegAudioSource source = new DiscordFfmpegAudioSource(process, 123L, () -> { }, () -> { });

        assertThat(waitUntil(() -> source.framesProduced() == 3)).isTrue();
        assertThat(source.canProvide()).isTrue();
        assertThat(source.provide20MsAudio()).isNotNull();
        assertThat(source.provide20MsAudio()).isNotNull();
        assertThat(source.provide20MsAudio()).isNotNull();

        assertThat(source.framesProduced()).isEqualTo(3);
        assertThat(source.framesDelivered()).isEqualTo(3);
        assertThat(source.bufferDepthMax()).isGreaterThan(0);
        assertThat(source.bufferDepthMin()).isGreaterThanOrEqualTo(0);
        assertThat(source.deliveryIntervals()).isGreaterThanOrEqualTo(2);
        assertThat(source.emptyPollsWhilePlaying()).isGreaterThanOrEqualTo(0);
        source.close();
    }

    @Test
    void shouldFillInitialBufferWithoutConsumingFrames() throws Exception {
        byte[] pcm = new byte[DiscordFfmpegAudioSource.PCM_FRAME_SIZE * 2];
        pcm[0] = 7;
        pcm[DiscordFfmpegAudioSource.PCM_FRAME_SIZE] = 8;
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(pcm));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(2, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        DiscordFfmpegAudioSource source = new DiscordFfmpegAudioSource(
                process,
                123L,
                10,
                () -> { },
                () -> { }
        );

        assertThat(source.awaitInitialBuffer(Duration.ofMillis(40), Duration.ofSeconds(1))).isTrue();
        assertThat(source.framesProduced()).isEqualTo(2);
        assertThat(source.framesDelivered()).isZero();
        assertThat(source.provide20MsAudio()).isNotNull();
        assertThat(source.provide20MsAudio()).isNotNull();
        source.close();
    }

    @Test
    void shouldRejectStartupWhenSourceEndsBeforeAnyInitialAudio() throws Exception {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(2, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(1);
        DiscordFfmpegAudioSource source = new DiscordFfmpegAudioSource(
                process,
                123L,
                10,
                () -> { },
                () -> { }
        );

        assertThat(source.awaitInitialBuffer(Duration.ofSeconds(10), Duration.ofMillis(100))).isFalse();
        assertThat(source.framesProduced()).isZero();
        source.close();
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
