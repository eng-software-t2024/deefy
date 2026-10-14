package br.com.deefy.discord;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscordGuildCommandExecutorTest {

    @Test
    void shouldSerializeOneGuildWhileAnotherGuildProgressesIndependently() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        DiscordGuildCommandExecutor executor = new DiscordGuildCommandExecutor(workers, 4);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondGuildCompleted = new CountDownLatch(1);
        try {
            CompletableFuture<String> first = executor.submit(1L, () -> {
                firstStarted.countDown();
                await(releaseFirst);
                return "first";
            });
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<String> secondSameGuild = executor.submit(1L, () -> "second");
            CompletableFuture<String> otherGuild = executor.submit(2L, () -> {
                secondGuildCompleted.countDown();
                return "other";
            });

            assertThat(secondGuildCompleted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(otherGuild.get(5, TimeUnit.SECONDS)).isEqualTo("other");
            assertThat(secondSameGuild).isNotDone();

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(secondSameGuild.get(5, TimeUnit.SECONDS)).isEqualTo("second");

            workers.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertThat(executor.activeLaneCount()).isZero();
        } finally {
            executor.close();
        }
    }

    @Test
    void shouldRejectClearlyWhenPerGuildQueueIsFull() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        DiscordGuildCommandExecutor executor = new DiscordGuildCommandExecutor(workers, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<Void> first = executor.submit(1L, () -> {
                running.countDown();
                await(release);
                return null;
            });
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> queued = executor.submit(1L, () -> null);
            CompletableFuture<Void> rejected = executor.submit(1L, () -> null);

            assertThatThrownBy(() -> rejected.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DiscordGuildCommandExecutor.GuildQueueSaturatedException.class);

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            queued.get(5, TimeUnit.SECONDS);
        } finally {
            executor.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test barrier timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        }
    }
}
