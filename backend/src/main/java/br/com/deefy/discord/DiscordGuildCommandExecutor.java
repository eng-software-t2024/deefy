package br.com.deefy.discord;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Serializes mutations inside each guild while allowing independent guilds to
 * progress concurrently. Lanes are bounded and removed as soon as they become
 * inactive, so idle guilds do not retain executors or queues.
 */
@Component
public final class DiscordGuildCommandExecutor implements AutoCloseable {

    static final int DEFAULT_MAX_QUEUED_PER_GUILD = 64;

    private final ExecutorService workers;
    private final int maxQueuedPerGuild;
    private final Map<Long, GuildLane> lanes = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DiscordGuildCommandExecutor() {
        this(
                Executors.newFixedThreadPool(
                        Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 8)),
                        runnable -> Thread.ofPlatform()
                                .daemon(true)
                                .name("deefy-discord-guild-command")
                                .unstarted(runnable)
                ),
                DEFAULT_MAX_QUEUED_PER_GUILD
        );
    }

    DiscordGuildCommandExecutor(ExecutorService workers, int maxQueuedPerGuild) {
        this.workers = Objects.requireNonNull(workers);
        if (maxQueuedPerGuild < 1) {
            throw new IllegalArgumentException("maxQueuedPerGuild must be positive");
        }
        this.maxQueuedPerGuild = maxQueuedPerGuild;
    }

    public <T> CompletableFuture<T> submit(long guildId, Supplier<T> operation) {
        Objects.requireNonNull(operation);
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed.get()) {
            result.completeExceptionally(new RejectedExecutionException("Discord guild executor is closed"));
            return result;
        }

        QueuedOperation<T> queued = new QueuedOperation<>(operation, result);
        AtomicBoolean startLane = new AtomicBoolean();
        AtomicBoolean accepted = new AtomicBoolean();
        lanes.compute(guildId, (ignored, existing) -> {
            GuildLane lane = existing == null ? new GuildLane(guildId) : existing;
            LaneAdmission admission = lane.enqueue(queued, maxQueuedPerGuild);
            accepted.set(admission.accepted());
            startLane.set(admission.startLane());
            return lane;
        });

        if (!accepted.get()) {
            result.completeExceptionally(new GuildQueueSaturatedException(guildId));
            retireEmptyLane(guildId);
        } else if (startLane.get()) {
            dispatch(lanes.get(guildId));
        }
        return result;
    }

    int activeLaneCount() {
        return lanes.size();
    }

    private void dispatch(GuildLane lane) {
        if (lane == null) {
            return;
        }
        try {
            workers.execute(() -> drain(lane));
        } catch (RejectedExecutionException exception) {
            lane.failQueued(exception);
            retireEmptyLane(lane.guildId());
        }
    }

    private void drain(GuildLane lane) {
        while (true) {
            QueuedOperation<?> operation = lane.poll();
            if (operation != null) {
                operation.execute();
                continue;
            }

            AtomicBoolean retired = new AtomicBoolean();
            lanes.compute(lane.guildId(), (ignored, current) -> {
                if (current == lane && lane.markStoppedIfEmpty()) {
                    retired.set(true);
                    return null;
                }
                return current;
            });
            if (retired.get()) {
                return;
            }
        }
    }

    private void retireEmptyLane(long guildId) {
        lanes.computeIfPresent(guildId, (ignored, lane) -> lane.markStoppedIfEmpty() ? null : lane);
    }

    @Override
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RejectedExecutionException failure = new RejectedExecutionException("Discord guild executor stopped");
        lanes.values().forEach(lane -> lane.failQueued(failure));
        lanes.clear();
        workers.shutdownNow();
    }

    private static final class GuildLane {

        private final long guildId;
        private final Deque<QueuedOperation<?>> queue = new ArrayDeque<>();
        private boolean running;

        private GuildLane(long guildId) {
            this.guildId = guildId;
        }

        private long guildId() {
            return guildId;
        }

        private synchronized LaneAdmission enqueue(QueuedOperation<?> operation, int capacity) {
            if (queue.size() >= capacity) {
                return new LaneAdmission(false, false);
            }
            queue.addLast(operation);
            boolean shouldStart = !running;
            running = true;
            return new LaneAdmission(true, shouldStart);
        }

        private synchronized QueuedOperation<?> poll() {
            return queue.pollFirst();
        }

        private synchronized boolean markStoppedIfEmpty() {
            if (!queue.isEmpty()) {
                return false;
            }
            running = false;
            return true;
        }

        private synchronized void failQueued(RuntimeException failure) {
            QueuedOperation<?> operation;
            while ((operation = queue.pollFirst()) != null) {
                operation.fail(failure);
            }
            running = false;
        }
    }

    private record LaneAdmission(boolean accepted, boolean startLane) {
    }

    private record QueuedOperation<T>(Supplier<T> operation, CompletableFuture<T> result) {

        private void execute() {
            if (result.isDone()) {
                return;
            }
            try {
                result.complete(operation.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }

        private void fail(RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    public static final class GuildQueueSaturatedException extends RejectedExecutionException {

        GuildQueueSaturatedException(long guildId) {
            super("Discord guild command queue is full for guild " + guildId);
        }
    }
}
