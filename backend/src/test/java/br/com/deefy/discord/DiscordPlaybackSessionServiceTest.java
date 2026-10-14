package br.com.deefy.discord;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordPlaybackSessionServiceTest {

    private final DiscordVoiceConnectionService voiceService = mock(DiscordVoiceConnectionService.class);
    private final DiscordAudioSourceFactory sourceFactory = mock(DiscordAudioSourceFactory.class);
    private final DiscordPlaybackSessionService service = new DiscordPlaybackSessionService(
            voiceService,
            sourceFactory
    );

    @Test
    void shouldKeepPlaybackSessionsIndependentByGuild() throws Exception {
        PlaybackFixture first = fixture(1L, 10L);
        PlaybackFixture second = fixture(2L, 20L);
        DiscordAudioSource firstSource = mock(DiscordAudioSource.class);
        DiscordAudioSource secondSource = mock(DiscordAudioSource.class);
        when(sourceFactory.open(eq(first.track), any(), any())).thenReturn(firstSource);
        when(sourceFactory.open(eq(second.track), any(), any())).thenReturn(secondSource);

        assertThat(service.start(first.guild, first.requester, first.track))
                .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.STARTED);
        assertThat(service.start(second.guild, second.requester, second.track))
                .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.STARTED);

        assertThat(service.activeSessionCount()).isEqualTo(2);
        assertThat(service.sessionFor(1L).orElseThrow().getCurrentTrack()).isEqualTo(first.track);
        assertThat(service.sessionFor(2L).orElseThrow().getCurrentTrack()).isEqualTo(second.track);
        verify(first.audioManager).setSendingHandler(firstSource);
        verify(second.audioManager).setSendingHandler(secondSource);
    }

    @Test
    void shouldRejectConcurrentTracksWithinSameGuildDeterministically() throws Exception {
        PlaybackFixture fixture = fixture(1L, 10L);
        DiscordAudioSource source = mock(DiscordAudioSource.class);
        CountDownLatch sourceCreationStarted = new CountDownLatch(1);
        CountDownLatch releaseSourceCreation = new CountDownLatch(1);
        when(sourceFactory.open(eq(fixture.track), any(), any())).thenAnswer(invocation -> {
            sourceCreationStarted.countDown();
            releaseSourceCreation.await(5, TimeUnit.SECONDS);
            return source;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<DiscordPlaybackSessionService.PlaybackStartOutcome> first = service.startAsync(
                    fixture.guild,
                    fixture.requester,
                    fixture.track,
                    "play-1",
                    ignored -> { }
            );
            assertThat(sourceCreationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<DiscordPlaybackSessionService.PlaybackStartOutcome> second = service.startAsync(
                    fixture.guild,
                    fixture.requester,
                    new DiscordTrack(2L, "Outra", "Artista", fixture.track.audioUrl()),
                    "play-2",
                    ignored -> { }
            );
            releaseSourceCreation.countDown();

            assertThat(second.get(5, TimeUnit.SECONDS).result())
                    .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.ALREADY_PLAYING);
            assertThat(first.get(5, TimeUnit.SECONDS).result())
                    .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.STARTED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldStopOnlyRequestedGuildAndCloseResourcesIdempotently() throws Exception {
        PlaybackFixture first = fixture(1L, 10L);
        PlaybackFixture second = fixture(2L, 20L);
        DiscordAudioSource firstSource = mock(DiscordAudioSource.class);
        DiscordAudioSource secondSource = mock(DiscordAudioSource.class);
        when(sourceFactory.open(eq(first.track), any(), any())).thenReturn(firstSource);
        when(sourceFactory.open(eq(second.track), any(), any())).thenReturn(secondSource);
        service.start(first.guild, first.requester, first.track);
        service.start(second.guild, second.requester, second.track);

        service.stop(first.guild);
        service.stop(first.guild);

        assertThat(service.sessionFor(1L)).isEmpty();
        assertThat(service.sessionFor(2L)).isPresent();
        verify(firstSource, times(1)).close();
        verify(first.audioManager, times(1)).setSendingHandler(null);
        verify(first.audioManager, never()).closeAudioConnection();
        verify(secondSource, never()).close();
        verify(second.audioManager, never()).setSendingHandler(null);
    }

    @Test
    void shouldCleanNaturalTerminationOnceWithoutTouchingAnotherGuild() throws Exception {
        PlaybackFixture first = fixture(1L, 10L);
        PlaybackFixture second = fixture(2L, 20L);
        DiscordAudioSource firstSource = mock(DiscordAudioSource.class);
        DiscordAudioSource secondSource = mock(DiscordAudioSource.class);
        ArgumentCaptor<Runnable> firstFrame = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> firstTermination = ArgumentCaptor.forClass(Runnable.class);
        when(sourceFactory.open(eq(first.track), firstFrame.capture(), firstTermination.capture()))
                .thenReturn(firstSource);
        when(sourceFactory.open(eq(second.track), any(), any())).thenReturn(secondSource);
        service.start(first.guild, first.requester, first.track);
        service.start(second.guild, second.requester, second.track);

        firstFrame.getValue().run();
        firstTermination.getValue().run();
        firstTermination.getValue().run();
        service.awaitGuild(1L);

        assertThat(service.sessionFor(1L)).isEmpty();
        assertThat(service.sessionFor(2L)).isPresent();
        assertThat(service.idleConnectionCount()).isEqualTo(1);
        verify(firstSource, times(1)).close();
        verify(first.audioManager, never()).closeAudioConnection();
        verify(secondSource, never()).close();
        verify(second.audioManager, never()).closeAudioConnection();
    }

    @Test
    void shouldDisconnectAfterControlledTenMinuteIdleDeadline() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> startupFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> idleFuture = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> scheduledTasks = ArgumentCaptor.forClass(Runnable.class);
        doReturn(startupFuture, idleFuture).when(scheduler)
                .schedule(scheduledTasks.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
        DiscordPlaybackSessionService controlledService = new DiscordPlaybackSessionService(
                voiceService,
                sourceFactory,
                scheduler,
                Duration.ofSeconds(20),
                Duration.ofMinutes(10)
        );
        PlaybackFixture fixture = fixture(1L, 10L);
        DiscordAudioSource source = mock(DiscordAudioSource.class);
        ArgumentCaptor<Runnable> firstFrame = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> termination = ArgumentCaptor.forClass(Runnable.class);
        when(sourceFactory.open(eq(fixture.track), firstFrame.capture(), termination.capture()))
                .thenReturn(source);

        assertThat(controlledService.start(fixture.guild, fixture.requester, fixture.track))
                .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.STARTED);
        firstFrame.getValue().run();
        termination.getValue().run();
        controlledService.awaitGuild(1L);

        verify(source).close();
        verify(fixture.audioManager, never()).closeAudioConnection();
        assertThat(controlledService.idleConnectionCount()).isEqualTo(1);
        assertThat(scheduledTasks.getAllValues()).hasSize(2);

        scheduledTasks.getAllValues().get(1).run();
        controlledService.awaitGuild(1L);

        verify(fixture.audioManager, times(1)).closeAudioConnection();
        assertThat(controlledService.idleConnectionCount()).isZero();
    }

    @Test
    void shouldCancelOldIdleTimerWhenValidPlaybackStarts() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> startupOne = mock(ScheduledFuture.class);
        ScheduledFuture<?> idleOne = mock(ScheduledFuture.class);
        ScheduledFuture<?> startupTwo = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> scheduledTasks = ArgumentCaptor.forClass(Runnable.class);
        doReturn(startupOne, idleOne, startupTwo).when(scheduler)
                .schedule(scheduledTasks.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
        DiscordPlaybackSessionService controlledService = new DiscordPlaybackSessionService(
                voiceService,
                sourceFactory,
                scheduler,
                Duration.ofSeconds(20),
                Duration.ofMinutes(10)
        );
        PlaybackFixture fixture = fixture(1L, 10L);
        DiscordAudioSource firstSource = mock(DiscordAudioSource.class);
        DiscordAudioSource secondSource = mock(DiscordAudioSource.class);
        ArgumentCaptor<Runnable> firstFrames = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> terminations = ArgumentCaptor.forClass(Runnable.class);
        when(sourceFactory.open(eq(fixture.track), firstFrames.capture(), terminations.capture()))
                .thenReturn(firstSource, secondSource);

        controlledService.start(fixture.guild, fixture.requester, fixture.track);
        firstFrames.getValue().run();
        terminations.getValue().run();
        controlledService.awaitGuild(1L);
        Runnable oldIdleTimer = scheduledTasks.getAllValues().get(1);

        assertThat(controlledService.start(fixture.guild, fixture.requester, fixture.track))
                .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.STARTED);
        oldIdleTimer.run();
        controlledService.awaitGuild(1L);

        assertThat(controlledService.sessionFor(1L)).isPresent();
        verify(idleOne).cancel(false);
        verify(fixture.audioManager, never()).closeAudioConnection();
        verify(secondSource, never()).close();
    }

    @Test
    void shouldScheduleIdleDisconnectForJoinAndCancelItOnStop() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> idleFuture = mock(ScheduledFuture.class);
        doReturn(idleFuture).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
        DiscordPlaybackSessionService controlledService = new DiscordPlaybackSessionService(
                voiceService,
                sourceFactory,
                scheduler,
                Duration.ofSeconds(20),
                Duration.ofMinutes(10)
        );
        PlaybackFixture fixture = fixture(1L, 10L);

        assertThat(controlledService.joinIdle(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.ALREADY_IN_CHANNEL);
        assertThat(controlledService.idleConnectionCount()).isEqualTo(1);

        controlledService.stop(fixture.guild);

        assertThat(controlledService.idleConnectionCount()).isZero();
        verify(idleFuture).cancel(false);
        verify(fixture.audioManager, never()).closeAudioConnection();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldCancelIdleTimerWhenVoiceDisconnectsExternally() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> idleFuture = mock(ScheduledFuture.class);
        doReturn(idleFuture).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
        DiscordPlaybackSessionService controlledService = new DiscordPlaybackSessionService(
                voiceService,
                sourceFactory,
                scheduler,
                Duration.ofSeconds(20),
                Duration.ofMinutes(10)
        );
        PlaybackFixture fixture = fixture(1L, 10L);
        ArgumentCaptor<Consumer<ConnectionStatus>> observer = ArgumentCaptor.forClass(Consumer.class);
        when(voiceService.join(eq(fixture.guild), eq(fixture.requester), observer.capture()))
                .thenReturn(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);

        controlledService.joinIdle(fixture.guild, fixture.requester);
        observer.getValue().accept(ConnectionStatus.DISCONNECTED_KICKED_FROM_CHANNEL);
        controlledService.awaitGuild(1L);

        assertThat(controlledService.idleConnectionCount()).isZero();
        verify(idleFuture).cancel(false);
        verify(fixture.audioManager, never()).closeAudioConnection();
    }

    @Test
    void shouldKeepIdleTimersIsolatedByGuild() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> secondFuture = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> scheduledTasks = ArgumentCaptor.forClass(Runnable.class);
        doReturn(firstFuture, secondFuture).when(scheduler)
                .schedule(scheduledTasks.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
        DiscordPlaybackSessionService controlledService = new DiscordPlaybackSessionService(
                voiceService,
                sourceFactory,
                scheduler,
                Duration.ofSeconds(20),
                Duration.ofMinutes(10)
        );
        PlaybackFixture first = fixture(1L, 10L);
        PlaybackFixture second = fixture(2L, 20L);

        controlledService.joinIdle(first.guild, first.requester);
        controlledService.joinIdle(second.guild, second.requester);
        scheduledTasks.getAllValues().get(0).run();
        controlledService.awaitGuild(1L);

        verify(first.audioManager).closeAudioConnection();
        verify(second.audioManager, never()).closeAudioConnection();
        assertThat(controlledService.idleConnectionCount()).isEqualTo(1);
    }

    @Test
    void shouldNotLetLateTerminationFromOldSessionRemoveNewSession() throws Exception {
        PlaybackFixture fixture = fixture(1L, 10L);
        DiscordAudioSource firstSource = mock(DiscordAudioSource.class);
        DiscordAudioSource secondSource = mock(DiscordAudioSource.class);
        ArgumentCaptor<Runnable> terminations = ArgumentCaptor.forClass(Runnable.class);
        when(sourceFactory.open(eq(fixture.track), any(), terminations.capture()))
                .thenReturn(firstSource)
                .thenReturn(secondSource);

        service.start(fixture.guild, fixture.requester, fixture.track);
        Runnable oldTermination = terminations.getValue();
        service.stop(fixture.guild);
        service.start(fixture.guild, fixture.requester, fixture.track);

        oldTermination.run();

        assertThat(service.sessionFor(1L)).isPresent();
        assertThat(service.sessionFor(1L).orElseThrow().getState())
                .isEqualTo(DiscordGuildPlaybackSession.PlaybackState.STARTING);
        verify(secondSource, never()).close();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldReleaseOnlyOwningSessionWhenVoiceDisconnects() throws Exception {
        PlaybackFixture fixture = fixture(1L, 10L);
        DiscordAudioSource source = mock(DiscordAudioSource.class);
        ArgumentCaptor<Consumer<ConnectionStatus>> observer = ArgumentCaptor.forClass(Consumer.class);
        when(voiceService.join(eq(fixture.guild), eq(fixture.requester), observer.capture()))
                .thenReturn(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
        when(sourceFactory.open(eq(fixture.track), any(), any())).thenReturn(source);

        assertThat(service.start(fixture.guild, fixture.requester, fixture.track))
                .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.STARTED);

        observer.getValue().accept(ConnectionStatus.DISCONNECTED_KICKED_FROM_CHANNEL);
        service.awaitGuild(1L);

        assertThat(service.sessionFor(1L)).isEmpty();
        verify(source).close();
        verify(fixture.audioManager, times(1)).closeAudioConnection();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldIgnoreLateTerminalCallbackFromOldVoiceAttempt() throws Exception {
        PlaybackFixture fixture = fixture(1L, 10L);
        DiscordAudioSource firstSource = mock(DiscordAudioSource.class);
        DiscordAudioSource secondSource = mock(DiscordAudioSource.class);
        ArgumentCaptor<Consumer<ConnectionStatus>> observers = ArgumentCaptor.forClass(Consumer.class);
        when(voiceService.join(eq(fixture.guild), eq(fixture.requester), observers.capture()))
                .thenReturn(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
        when(sourceFactory.open(eq(fixture.track), any(), any()))
                .thenReturn(firstSource)
                .thenReturn(secondSource);

        assertThat(service.start(fixture.guild, fixture.requester, fixture.track))
                .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.STARTED);
        Consumer<ConnectionStatus> oldObserver = observers.getValue();
        service.stop(fixture.guild);
        assertThat(service.start(fixture.guild, fixture.requester, fixture.track))
                .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.STARTED);

        oldObserver.accept(ConnectionStatus.DISCONNECTED_LOST_PERMISSION);

        assertThat(service.sessionFor(1L)).isPresent();
        verify(secondSource, never()).close();
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldCloseTerminalFailureOnceWithoutAffectingAnotherGuild() throws Exception {
        PlaybackFixture first = fixture(1L, 10L);
        PlaybackFixture second = fixture(2L, 20L);
        DiscordAudioSource firstSource = mock(DiscordAudioSource.class);
        DiscordAudioSource secondSource = mock(DiscordAudioSource.class);
        ArgumentCaptor<Consumer<ConnectionStatus>> firstObserver = ArgumentCaptor.forClass(Consumer.class);
        when(voiceService.join(eq(first.guild), eq(first.requester), firstObserver.capture()))
                .thenReturn(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
        when(sourceFactory.open(eq(first.track), any(), any())).thenReturn(firstSource);
        when(sourceFactory.open(eq(second.track), any(), any())).thenReturn(secondSource);

        service.start(first.guild, first.requester, first.track);
        service.start(second.guild, second.requester, second.track);
        firstObserver.getValue().accept(ConnectionStatus.ERROR_CANNOT_RESUME);
        firstObserver.getValue().accept(ConnectionStatus.ERROR_CANNOT_RESUME);
        service.awaitGuild(1L);

        assertThat(service.sessionFor(1L)).isEmpty();
        assertThat(service.sessionFor(2L)).isPresent();
        verify(firstSource, times(1)).close();
        verify(first.audioManager, times(1)).closeAudioConnection();
        verify(secondSource, never()).close();
        verify(second.audioManager, never()).closeAudioConnection();
    }

    @Test
    void shouldCleanSessionWhenAudioSourceCannotStart() throws Exception {
        PlaybackFixture fixture = fixture(1L, 10L);
        when(sourceFactory.open(eq(fixture.track), any(), any()))
                .thenThrow(new IllegalStateException("simulated"));

        assertThat(service.start(fixture.guild, fixture.requester, fixture.track))
                .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.AUDIO_SOURCE_FAILED);

        assertThat(service.sessionFor(1L)).isEmpty();
        assertThat(service.idleConnectionCount()).isZero();
        verify(fixture.audioManager).closeAudioConnection();
    }

    @Test
    void shouldCancelSilentStartupAndReportTimeoutWithoutAffectingOtherGuilds() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        DiscordPlaybackSessionService timeoutService = new DiscordPlaybackSessionService(
                voiceService,
                sourceFactory,
                scheduler,
                java.time.Duration.ZERO
        );
        PlaybackFixture fixture = fixture(1L, 10L);
        DiscordAudioSource source = mock(DiscordAudioSource.class);
        CountDownLatch timeoutReported = new CountDownLatch(1);
        when(sourceFactory.open(eq(fixture.track), any(), any())).thenReturn(source);

        try {
            assertThat(timeoutService.start(
                    fixture.guild,
                    fixture.requester,
                    fixture.track,
                    progress -> {
                        if (progress == DiscordPlaybackSessionService.PlaybackProgress.START_TIMEOUT) {
                            timeoutReported.countDown();
                        }
                    }
            )).isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.STARTED);

            assertThat(timeoutReported.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(timeoutService.sessionFor(1L)).isEmpty();
            verify(source).close();
            verify(fixture.audioManager).closeAudioConnection();
        } finally {
            timeoutService.closeAll();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldReportVoiceReadyAndPcmAvailabilityWithoutClaimingAudioWasHeard() throws Exception {
        PlaybackFixture fixture = fixture(1L, 10L);
        DiscordAudioSource source = mock(DiscordAudioSource.class);
        ArgumentCaptor<Consumer<ConnectionStatus>> voiceObserver = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Runnable> firstFrame = ArgumentCaptor.forClass(Runnable.class);
        List<DiscordPlaybackSessionService.PlaybackProgress> progress = new CopyOnWriteArrayList<>();
        when(voiceService.join(eq(fixture.guild), eq(fixture.requester), voiceObserver.capture()))
                .thenReturn(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
        when(sourceFactory.open(eq(fixture.track), firstFrame.capture(), any())).thenReturn(source);

        assertThat(service.start(fixture.guild, fixture.requester, fixture.track, progress::add))
                .isEqualTo(DiscordPlaybackSessionService.PlaybackStartResult.STARTED);

        voiceObserver.getValue().accept(ConnectionStatus.CONNECTED);
        firstFrame.getValue().run();
        service.awaitGuild(1L);

        assertThat(progress).containsExactly(
                DiscordPlaybackSessionService.PlaybackProgress.VOICE_CONNECTED,
                DiscordPlaybackSessionService.PlaybackProgress.AUDIO_FRAMES_AVAILABLE
        );
    }

    private PlaybackFixture fixture(long guildId, long channelId) {
        Guild guild = mock(Guild.class);
        Member requester = mock(Member.class);
        SelfMember selfMember = mock(SelfMember.class);
        GuildVoiceState voiceState = mock(GuildVoiceState.class);
        AudioChannelUnion channelUnion = mock(AudioChannelUnion.class);
        VoiceChannel voiceChannel = mock(VoiceChannel.class);
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
        when(requester.getVoiceState()).thenReturn(voiceState);
        when(voiceState.getChannel()).thenReturn(channelUnion);
        when(channelUnion.getType()).thenReturn(ChannelType.VOICE);
        when(channelUnion.asVoiceChannel()).thenReturn(voiceChannel);
        when(voiceChannel.getIdLong()).thenReturn(channelId);
        when(selfMember.hasPermission(voiceChannel, Permission.VOICE_SPEAK)).thenReturn(true);
        when(voiceService.join(eq(guild), eq(requester), any()))
                .thenReturn(DiscordVoiceConnectionService.VoiceCommandResult.ALREADY_IN_CHANNEL);

        return new PlaybackFixture(guild, requester, voiceChannel, audioManager, track);
    }

    private record PlaybackFixture(
            Guild guild,
            Member requester,
            VoiceChannel voiceChannel,
            AudioManager audioManager,
            DiscordTrack track
    ) {
    }
}
