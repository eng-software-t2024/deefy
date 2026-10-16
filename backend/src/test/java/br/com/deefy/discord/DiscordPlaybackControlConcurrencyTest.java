package br.com.deefy.discord;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordPlaybackControlConcurrencyTest {

    @Test
    void shouldApplyOnlyOneOfTwoConcurrentPauseInteractions() throws Exception {
        try (Harness harness = new Harness()) {
            Fixture fixture = harness.fixture(1L, 10L);
            DiscordAudioSource source = harness.sourceFor(fixture);
            Started started = harness.startPlaying(fixture, "play-1");
            harness.bind(started, 100L);

            Pair<DiscordPlaybackSessionService.PlaybackControlResult> results = concurrently(
                    () -> harness.control(fixture, pauseButton(started, 100L, "pause-1")),
                    () -> harness.control(fixture, pauseButton(started, 100L, "pause-2"))
            );

            assertThat(results.values())
                    .extracting(DiscordPlaybackSessionService.PlaybackControlResult::status)
                    .containsExactlyInAnyOrder(
                            DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED,
                            DiscordPlaybackSessionService.PlaybackControlStatus.ALREADY_APPLIED
                    );
            verify(source, times(1)).pause();
            assertThat(harness.service.stateFor(1L))
                    .isEqualTo(DiscordGuildPlaybackSession.PlaybackState.PAUSED);
        }
    }

    @Test
    void shouldSerializePauseAgainstStopAndCloseResourcesOnlyOnce() throws Exception {
        try (Harness harness = new Harness()) {
            Fixture fixture = harness.fixture(1L, 10L);
            DiscordAudioSource source = harness.sourceFor(fixture);
            Started started = harness.startPlaying(fixture, "play-1");

            Pair<DiscordPlaybackSessionService.PlaybackControlResult> results = concurrently(
                    () -> harness.control(fixture, slash(DiscordPlaybackSessionService.PlaybackControlAction.PAUSE, "pause-1")),
                    () -> harness.control(fixture, slash(DiscordPlaybackSessionService.PlaybackControlAction.STOP, "stop-1"))
            );

            assertThat(results.values())
                    .anySatisfy(result -> assertThat(result.status())
                            .isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED));
            assertThat(harness.service.sessionFor(1L)).isEmpty();
            assertThat(harness.service.idleConnectionCount()).isEqualTo(1);
            verify(source, atMostOnce()).pause();
            verify(source, times(1)).close();
            verify(fixture.audioManager(), times(1)).setSendingHandler(null);
            assertThat(started.sessionId()).isNotBlank();
        }
    }

    @Test
    void shouldAdvanceExactlyOnceWhenNaturalEndAndSkipAreQueuedTogether() throws Exception {
        DiscordGuildCommandExecutor executor = new DiscordGuildCommandExecutor();
        try (Harness harness = new Harness(executor)) {
            Fixture fixture = harness.fixture(1L, 10L);
            DiscordTrack secondTrack = new DiscordTrack(2L, "Segunda", "Artista", fixture.track().audioUrl());
            DiscordTrack thirdTrack = new DiscordTrack(3L, "Terceira", "Artista", fixture.track().audioUrl());
            DiscordAudioSource firstSource = harness.sourceFor(fixture);
            DiscordAudioSource secondSource = harness.sourceFor(fixture, secondTrack);
            DiscordAudioSource thirdSource = harness.sourceFor(fixture, thirdTrack);

            Started started = harness.startPlaying(fixture, "play-1");
            assertThat(harness.service.start(fixture.guild(), fixture.requester(), secondTrack))
                    .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.QUEUED);
            assertThat(harness.service.start(fixture.guild(), fixture.requester(), thirdTrack))
                    .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.QUEUED);

            CountDownLatch laneStarted = new CountDownLatch(1);
            CountDownLatch releaseLane = new CountDownLatch(1);
            executor.submit(fixture.guild().getIdLong(), () -> {
                laneStarted.countDown();
                try {
                    releaseLane.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            assertThat(laneStarted.await(5, TimeUnit.SECONDS)).isTrue();
            harness.terminationFor(fixture.track()).run();
            CompletableFuture<DiscordPlaybackSessionService.PlaybackControlResult> skip = harness.service.controlAsync(
                    fixture.guild(),
                    fixture.requester(),
                    slash(DiscordPlaybackSessionService.PlaybackControlAction.SKIP, "skip-racing-natural-end")
            );
            releaseLane.countDown();
            assertThat(skip.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.EXPIRED_CONTROL);
            harness.service.awaitGuild(fixture.guild().getIdLong());

            assertThat(harness.service.sessionFor(fixture.guild().getIdLong()).orElseThrow().getCurrentTrack())
                    .isEqualTo(secondTrack);
            assertThat(harness.service.queueSnapshot(fixture.guild().getIdLong()).pending())
                    .extracting(DiscordPlaybackSessionService.QueueEntryView::track)
                    .containsExactly(thirdTrack);
            verify(firstSource).close();
            verify(secondSource, never()).close();
            verify(thirdSource, never()).close();
            assertThat(started.sessionId()).isNotBlank();
        }
    }

    @Test
    void shouldLetResumeWinAgainstAlreadyQueuedPauseTimeout() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> startup = mock(ScheduledFuture.class);
        ScheduledFuture<?> pauseTimeout = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        doReturn(startup, pauseTimeout).when(scheduler)
                .schedule(tasks.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
        try (Harness harness = new Harness(scheduler)) {
            Fixture fixture = harness.fixture(1L, 10L);
            DiscordAudioSource source = harness.sourceFor(fixture);
            harness.startPlaying(fixture, "play-1");
            assertThat(harness.control(
                    fixture,
                    slash(DiscordPlaybackSessionService.PlaybackControlAction.PAUSE, "pause-1")
            ).status()).isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED);
            Runnable oldPauseTimeout = tasks.getAllValues().get(1);

            CompletableFuture<DiscordPlaybackSessionService.PlaybackControlResult> resume =
                    harness.service.controlAsync(
                            fixture.guild(),
                            fixture.requester(),
                            slash(DiscordPlaybackSessionService.PlaybackControlAction.RESUME, "resume-1")
                    );
            oldPauseTimeout.run();

            assertThat(resume.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED);
            harness.service.awaitGuild(1L);
            assertThat(harness.service.stateFor(1L))
                    .isEqualTo(DiscordGuildPlaybackSession.PlaybackState.PLAYING);
            verify(source).resume();
            verify(source, never()).close();
            verify(pauseTimeout).cancel(false);
        }
    }

    @Test
    void shouldIgnoreTimeoutFromAnOlderPauseGeneration() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> startup = mock(ScheduledFuture.class);
        ScheduledFuture<?> firstPauseTimeout = mock(ScheduledFuture.class);
        ScheduledFuture<?> secondPauseTimeout = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        doReturn(startup, firstPauseTimeout, secondPauseTimeout).when(scheduler)
                .schedule(tasks.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
        try (Harness harness = new Harness(scheduler)) {
            Fixture fixture = harness.fixture(1L, 10L);
            DiscordAudioSource source = harness.sourceFor(fixture);
            harness.startPlaying(fixture, "play-1");

            assertThat(harness.control(
                    fixture,
                    slash(DiscordPlaybackSessionService.PlaybackControlAction.PAUSE, "pause-1")
            ).status()).isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED);
            Runnable staleTimeout = tasks.getAllValues().get(1);
            assertThat(harness.control(
                    fixture,
                    slash(DiscordPlaybackSessionService.PlaybackControlAction.RESUME, "resume-1")
            ).status()).isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED);
            assertThat(harness.control(
                    fixture,
                    slash(DiscordPlaybackSessionService.PlaybackControlAction.PAUSE, "pause-2")
            ).status()).isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED);

            staleTimeout.run();
            harness.service.awaitGuild(1L);

            assertThat(harness.service.stateFor(1L))
                    .isEqualTo(DiscordGuildPlaybackSession.PlaybackState.PAUSED);
            verify(source, never()).close();

            tasks.getAllValues().get(2).run();
            harness.service.awaitGuild(1L);

            assertThat(harness.service.sessionFor(1L)).isEmpty();
            verify(source, times(1)).close();
        }
    }

    @Test
    void shouldExpireAProlongedPauseAndPublishItsCleanupState() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> startup = mock(ScheduledFuture.class);
        ScheduledFuture<?> pauseTimeout = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        doReturn(startup, pauseTimeout).when(scheduler)
                .schedule(tasks.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
        try (Harness harness = new Harness(scheduler)) {
            Fixture fixture = harness.fixture(1L, 10L);
            DiscordAudioSource source = harness.sourceFor(fixture);
            List<DiscordPlaybackSessionService.PlaybackProgress> progress = new CopyOnWriteArrayList<>();
            harness.startPlaying(fixture, "play-1", progress::add);

            assertThat(harness.control(
                    fixture,
                    slash(DiscordPlaybackSessionService.PlaybackControlAction.PAUSE, "pause-1")
            ).status()).isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED);

            tasks.getAllValues().get(1).run();
            harness.service.awaitGuild(1L);

            assertThat(progress).containsSubsequence(
                    DiscordPlaybackSessionService.PlaybackProgress.AUDIO_FRAMES_AVAILABLE,
                    DiscordPlaybackSessionService.PlaybackProgress.PAUSED,
                    DiscordPlaybackSessionService.PlaybackProgress.PAUSE_TIMEOUT
            );
            assertThat(harness.service.sessionFor(1L)).isEmpty();
            verify(source, times(1)).close();
            verify(fixture.audioManager(), times(1)).closeAudioConnection();
        }
    }

    @Test
    void shouldRejectOldButtonAfterAReplacementSessionStarts() throws Exception {
        try (Harness harness = new Harness()) {
            Fixture fixture = harness.fixture(1L, 10L);
            DiscordAudioSource first = harness.sourceFor(fixture);
            Started oldSession = harness.startPlaying(fixture, "play-1");
            harness.bind(oldSession, 100L);
            assertThat(harness.control(
                    fixture,
                    slash(DiscordPlaybackSessionService.PlaybackControlAction.STOP, "stop-1")
            ).status()).isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED);

            DiscordAudioSource second = harness.sourceFor(fixture);
            Started newSession = harness.startPlaying(fixture, "play-2");
            harness.bind(newSession, 200L);

            DiscordPlaybackSessionService.PlaybackControlResult stale = harness.control(
                    fixture,
                    pauseButton(oldSession, 100L, "old-button")
            );

            assertThat(stale.status())
                    .isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.EXPIRED_CONTROL);
            assertThat(harness.service.sessionFor(1L).orElseThrow().getSessionId())
                    .isEqualTo(newSession.sessionId());
            verify(first).close();
            verify(second, never()).pause();
            verify(second, never()).close();
        }
    }

    @Test
    void shouldDeduplicateRedeliveredInteractionId() throws Exception {
        try (Harness harness = new Harness()) {
            Fixture fixture = harness.fixture(1L, 10L);
            DiscordAudioSource source = harness.sourceFor(fixture);
            Started started = harness.startPlaying(fixture, "play-1");
            harness.bind(started, 100L);
            DiscordPlaybackSessionService.PlaybackControlRequest repeated =
                    pauseButton(started, 100L, "same-interaction");

            DiscordPlaybackSessionService.PlaybackControlResult first = harness.control(fixture, repeated);
            DiscordPlaybackSessionService.PlaybackControlResult second = harness.control(fixture, repeated);

            assertThat(first.status()).isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED);
            assertThat(second.status()).isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.DUPLICATE);
            verify(source, times(1)).pause();
        }
    }

    @Test
    void shouldRejectControllerFromAnotherVoiceChannelAtActionTime() throws Exception {
        try (Harness harness = new Harness()) {
            Fixture fixture = harness.fixture(1L, 10L);
            DiscordAudioSource source = harness.sourceFor(fixture);
            harness.startPlaying(fixture, "play-1");
            Member outsider = harness.memberInChannel(99L);

            DiscordPlaybackSessionService.PlaybackControlResult result = harness.service.controlAsync(
                    fixture.guild(),
                    outsider,
                    slash(DiscordPlaybackSessionService.PlaybackControlAction.PAUSE, "pause-other-channel")
            ).get(5, TimeUnit.SECONDS);

            assertThat(result.status())
                    .isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.DIFFERENT_CHANNEL);
            verify(source, never()).pause();
        }
    }

    @Test
    void shouldRejectControlWhenBotHasLeftTheSessionVoiceChannel() throws Exception {
        try (Harness harness = new Harness()) {
            Fixture fixture = harness.fixture(1L, 10L);
            DiscordAudioSource source = harness.sourceFor(fixture);
            harness.startPlaying(fixture, "play-1");
            AudioChannelUnion movedChannel = mock(AudioChannelUnion.class);
            when(movedChannel.getType()).thenReturn(ChannelType.VOICE);
            when(movedChannel.getIdLong()).thenReturn(99L);
            when(fixture.audioManager().getConnectedChannel()).thenReturn(movedChannel);

            DiscordPlaybackSessionService.PlaybackControlResult result = harness.control(
                    fixture,
                    slash(DiscordPlaybackSessionService.PlaybackControlAction.PAUSE, "pause-after-move")
            );

            assertThat(result.status())
                    .isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.EXPIRED_CONTROL);
            verify(source, never()).pause();
        }
    }

    @Test
    void shouldKeepControlStateIsolatedBetweenGuilds() throws Exception {
        try (Harness harness = new Harness()) {
            Fixture first = harness.fixture(1L, 10L);
            Fixture second = harness.fixture(2L, 20L);
            DiscordAudioSource firstSource = harness.sourceFor(first);
            DiscordAudioSource secondSource = harness.sourceFor(second);
            harness.startPlaying(first, "play-1");
            harness.startPlaying(second, "play-2");

            assertThat(harness.control(
                    first,
                    slash(DiscordPlaybackSessionService.PlaybackControlAction.PAUSE, "pause-1")
            ).status()).isEqualTo(DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED);

            assertThat(harness.service.stateFor(1L))
                    .isEqualTo(DiscordGuildPlaybackSession.PlaybackState.PAUSED);
            assertThat(harness.service.stateFor(2L))
                    .isEqualTo(DiscordGuildPlaybackSession.PlaybackState.PLAYING);
            verify(firstSource).pause();
            verify(secondSource, never()).pause();
        }
    }

    @Test
    void shouldLeaveImmediatelyWithoutWaitingForIdleDeadline() throws Exception {
        try (Harness harness = new Harness()) {
            Fixture fixture = harness.fixture(1L, 10L);
            DiscordAudioSource source = harness.sourceFor(fixture);
            harness.startPlaying(fixture, "play-1");
            when(harness.voiceService.leave(eq(fixture.guild()), eq(fixture.requester()), any()))
                    .thenAnswer(invocation -> {
                        invocation.<Runnable>getArgument(2).run();
                        return DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_STARTED;
                    });

            DiscordVoiceConnectionService.VoiceCommandResult result = harness.service.leaveAsync(
                    fixture.guild(),
                    fixture.requester(),
                    "leave-1"
            ).get(5, TimeUnit.SECONDS);

            assertThat(result).isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_STARTED);
            assertThat(harness.service.sessionFor(1L)).isEmpty();
            assertThat(harness.service.idleConnectionCount()).isZero();
            verify(source, times(1)).close();
        }
    }

    private DiscordPlaybackSessionService.PlaybackControlRequest pauseButton(
            Started started,
            long messageId,
            String interactionId
    ) {
        return DiscordPlaybackSessionService.PlaybackControlRequest.button(
                DiscordPlaybackSessionService.PlaybackControlAction.PAUSE,
                started.sessionId(),
                messageId,
                interactionId
        );
    }

    private DiscordPlaybackSessionService.PlaybackControlRequest slash(
            DiscordPlaybackSessionService.PlaybackControlAction action,
            String interactionId
    ) {
        return DiscordPlaybackSessionService.PlaybackControlRequest.slash(action, interactionId);
    }

    private <T> Pair<T> concurrently(ThrowingSupplier<T> first, ThrowingSupplier<T> second) throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> firstResult = callers.submit(() -> awaitAndGet(ready, start, first));
            Future<T> secondResult = callers.submit(() -> awaitAndGet(ready, start, second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return new Pair<>(
                    firstResult.get(5, TimeUnit.SECONDS),
                    secondResult.get(5, TimeUnit.SECONDS)
            );
        } finally {
            callers.shutdownNow();
        }
    }

    private <T> T awaitAndGet(
            CountDownLatch ready,
            CountDownLatch start,
            ThrowingSupplier<T> operation
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrency barrier timed out");
        }
        return operation.get();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record Pair<T>(T first, T second) {
        private java.util.List<T> values() {
            return java.util.List.of(first, second);
        }
    }

    private record Started(long guildId, String sessionId) {
    }

    private record Fixture(
            Guild guild,
            Member requester,
            VoiceChannel voiceChannel,
            AudioManager audioManager,
            DiscordTrack track
    ) {
    }

    private static final class Harness implements AutoCloseable {
        private final DiscordVoiceConnectionService voiceService = mock(DiscordVoiceConnectionService.class);
        private final DiscordAudioSourceFactory sourceFactory = mock(DiscordAudioSourceFactory.class);
        private final DiscordGuildCommandExecutor guildExecutor;
        private final ScheduledExecutorService scheduler;
        private final DiscordPlaybackSessionService service;
        private final Map<Long, DiscordAudioSource> sources = new ConcurrentHashMap<>();
        private final Map<Long, Runnable> firstFrames = new ConcurrentHashMap<>();
        private final Map<Long, Runnable> terminations = new ConcurrentHashMap<>();

        private Harness() {
            this(new DiscordGuildCommandExecutor(), Executors.newSingleThreadScheduledExecutor());
        }

        private Harness(ScheduledExecutorService scheduler) {
            this(new DiscordGuildCommandExecutor(), scheduler);
        }

        private Harness(DiscordGuildCommandExecutor guildExecutor) {
            this(guildExecutor, Executors.newSingleThreadScheduledExecutor());
        }

        private Harness(DiscordGuildCommandExecutor guildExecutor, ScheduledExecutorService scheduler) {
            this.guildExecutor = guildExecutor;
            this.scheduler = scheduler;
            this.service = new DiscordPlaybackSessionService(
                    voiceService,
                    sourceFactory,
                    guildExecutor,
                    scheduler,
                    Duration.ofSeconds(20),
                    Duration.ofMinutes(10)
            );
            try {
                when(sourceFactory.open(any(), any(), any())).thenAnswer(invocation -> {
                    DiscordTrack track = invocation.getArgument(0);
                    firstFrames.put(track.id(), invocation.getArgument(1));
                    terminations.put(track.id(), invocation.getArgument(2));
                    return sources.get(track.id());
                });
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private Fixture fixture(long guildId, long channelId) {
            Guild guild = mock(Guild.class);
            Member requester = memberInChannel(channelId);
            SelfMember selfMember = mock(SelfMember.class);
            AudioChannelUnion requesterChannel = requester.getVoiceState().getChannel();
            VoiceChannel voiceChannel = requesterChannel.asVoiceChannel();
            AudioManager audioManager = mock(AudioManager.class);
            DiscordTrack track = new DiscordTrack(
                    guildId,
                    "Faixa " + guildId,
                    "Artista",
                    "https://deefy.olua.me/media/" + guildId + ".mp3"
            );
            when(guild.getIdLong()).thenReturn(guildId);
            when(guild.getAudioManager()).thenReturn(audioManager);
            when(guild.getSelfMember()).thenReturn(selfMember);
            when(audioManager.getConnectedChannel()).thenReturn(requesterChannel);
            when(selfMember.hasPermission(voiceChannel, Permission.VOICE_SPEAK)).thenReturn(true);
            when(voiceService.join(eq(guild), eq(requester), any()))
                    .thenReturn(DiscordVoiceConnectionService.VoiceCommandResult.ALREADY_IN_CHANNEL);
            return new Fixture(guild, requester, voiceChannel, audioManager, track);
        }

        private Member memberInChannel(long channelId) {
            Member member = mock(Member.class);
            GuildVoiceState voiceState = mock(GuildVoiceState.class);
            AudioChannelUnion union = mock(AudioChannelUnion.class);
            VoiceChannel voiceChannel = mock(VoiceChannel.class);
            when(member.getVoiceState()).thenReturn(voiceState);
            when(voiceState.getChannel()).thenReturn(union);
            when(union.getType()).thenReturn(ChannelType.VOICE);
            when(union.asVoiceChannel()).thenReturn(voiceChannel);
            when(union.getIdLong()).thenReturn(channelId);
            when(voiceChannel.getIdLong()).thenReturn(channelId);
            return member;
        }

        private DiscordAudioSource sourceFor(Fixture fixture) {
            return sourceFor(fixture, fixture.track());
        }

        private DiscordAudioSource sourceFor(Fixture fixture, DiscordTrack track) {
            DiscordAudioSource source = mock(DiscordAudioSource.class);
            sources.put(track.id(), source);
            return source;
        }

        private Runnable terminationFor(DiscordTrack track) {
            return terminations.get(track.id());
        }

        private Started startPlaying(Fixture fixture, String interactionId) throws Exception {
            return startPlaying(fixture, interactionId, ignored -> { });
        }

        private Started startPlaying(
                Fixture fixture,
                String interactionId,
                Consumer<DiscordPlaybackSessionService.PlaybackProgress> progressObserver
        ) throws Exception {
            DiscordPlaybackSessionService.PlaybackStartOutcome outcome = service.startAsync(
                    fixture.guild(),
                    fixture.requester(),
                    fixture.track(),
                    interactionId,
                    progressObserver
            ).get(5, TimeUnit.SECONDS);
            assertThat(outcome.result()).isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.STARTED);
            firstFrames.get(fixture.track().id()).run();
            service.awaitGuild(fixture.guild().getIdLong());
            assertThat(service.stateFor(fixture.guild().getIdLong()))
                    .isEqualTo(DiscordGuildPlaybackSession.PlaybackState.PLAYING);
            return new Started(fixture.guild().getIdLong(), outcome.sessionId());
        }

        private void bind(Started started, long messageId) throws Exception {
            assertThat(service.bindControlMessageAsync(
                    started.guildId(),
                    started.sessionId(),
                    messageId
            ).get(5, TimeUnit.SECONDS)).isTrue();
        }

        private DiscordPlaybackSessionService.PlaybackControlResult control(
                Fixture fixture,
                DiscordPlaybackSessionService.PlaybackControlRequest request
        ) throws Exception {
            return service.controlAsync(fixture.guild(), fixture.requester(), request)
                    .get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            service.closeAll();
            if (!scheduler.isShutdown()) {
                scheduler.shutdownNow();
            }
        }
    }
}
