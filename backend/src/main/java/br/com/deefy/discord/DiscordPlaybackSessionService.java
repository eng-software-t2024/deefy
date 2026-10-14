package br.com.deefy.discord;

import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class DiscordPlaybackSessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordPlaybackSessionService.class);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration INACTIVITY_TIMEOUT = Duration.ofMinutes(10);
    private static final int INTERACTION_DEDUPLICATION_LIMIT = 128;

    private final DiscordVoiceConnectionService voiceConnectionService;
    private final DiscordAudioSourceFactory audioSourceFactory;
    private final DiscordGuildCommandExecutor guildExecutor;
    private final ExecutorService audioSetupExecutor;
    private final ScheduledExecutorService scheduler;
    private final Duration startupTimeout;
    private final Duration inactivityTimeout;
    private final ConcurrentMap<Long, DiscordGuildPlaybackSession> sessionsByGuild = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, IdleVoiceConnection> idleConnectionsByGuild = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, LinkedHashMap<String, Boolean>> recentInteractionsByGuild =
            new ConcurrentHashMap<>();

    @Autowired
    public DiscordPlaybackSessionService(
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordAudioSourceFactory audioSourceFactory,
            DiscordGuildCommandExecutor guildExecutor
    ) {
        this(
                voiceConnectionService,
                audioSourceFactory,
                guildExecutor,
                Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform()
                        .daemon(true)
                        .name("deefy-discord-playback-timeout")
                        .unstarted(runnable)),
                STARTUP_TIMEOUT,
                INACTIVITY_TIMEOUT
        );
    }

    DiscordPlaybackSessionService(
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordAudioSourceFactory audioSourceFactory
    ) {
        this(voiceConnectionService, audioSourceFactory, new DiscordGuildCommandExecutor());
    }

    DiscordPlaybackSessionService(
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordAudioSourceFactory audioSourceFactory,
            ScheduledExecutorService scheduler,
            Duration startupTimeout
    ) {
        this(
                voiceConnectionService,
                audioSourceFactory,
                new DiscordGuildCommandExecutor(),
                scheduler,
                startupTimeout,
                INACTIVITY_TIMEOUT
        );
    }

    DiscordPlaybackSessionService(
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordAudioSourceFactory audioSourceFactory,
            ScheduledExecutorService scheduler,
            Duration startupTimeout,
            Duration inactivityTimeout
    ) {
        this(
                voiceConnectionService,
                audioSourceFactory,
                new DiscordGuildCommandExecutor(),
                scheduler,
                startupTimeout,
                inactivityTimeout
        );
    }

    DiscordPlaybackSessionService(
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordAudioSourceFactory audioSourceFactory,
            DiscordGuildCommandExecutor guildExecutor,
            ScheduledExecutorService scheduler,
            Duration startupTimeout,
            Duration inactivityTimeout
    ) {
        this.voiceConnectionService = voiceConnectionService;
        this.audioSourceFactory = audioSourceFactory;
        this.guildExecutor = guildExecutor;
        this.audioSetupExecutor = newAudioSetupExecutor();
        this.scheduler = scheduler;
        this.startupTimeout = startupTimeout;
        this.inactivityTimeout = inactivityTimeout;
    }

    public CompletableFuture<DiscordVoiceConnectionService.VoiceCommandResult> joinIdleAsync(
            Guild guild,
            Member requester,
            String interactionId
    ) {
        if (guild == null) {
            return CompletableFuture.completedFuture(
                    DiscordVoiceConnectionService.VoiceCommandResult.USER_NOT_IN_VOICE
            );
        }
        return guildExecutor.submit(guild.getIdLong(), () -> joinIdleInGuild(guild, requester, interactionId));
    }

    public DiscordVoiceConnectionService.VoiceCommandResult joinIdle(Guild guild, Member requester) {
        return join(joinIdleAsync(guild, requester, null));
    }

    public CompletableFuture<PlaybackStartOutcome> startAsync(
            Guild guild,
            Member requester,
            DiscordTrack track,
            String interactionId,
            Consumer<PlaybackProgress> progressObserver
    ) {
        if (guild == null) {
            return CompletableFuture.completedFuture(outcome(PlaybackStartResult.USER_NOT_IN_VOICE, null));
        }
        return guildExecutor.submit(
                guild.getIdLong(),
                () -> reserveStartInGuild(guild, requester, track, interactionId, progressObserver)
        ).thenCompose(reservation -> {
            if (reservation.outcome() != null) {
                return CompletableFuture.completedFuture(reservation.outcome());
            }
            return openAudioSourceAsync(reservation.session())
                    .thenCompose(opening -> guildExecutor.submit(
                            guild.getIdLong(),
                            () -> completeStartInGuild(reservation.session(), opening)
                    ));
        });
    }

    public PlaybackStartResult start(Guild guild, Member requester, DiscordTrack track) {
        return start(guild, requester, track, ignored -> { });
    }

    public PlaybackStartResult start(
            Guild guild,
            Member requester,
            DiscordTrack track,
            Consumer<PlaybackProgress> progressObserver
    ) {
        return join(startAsync(guild, requester, track, null, progressObserver)).result();
    }

    public CompletableFuture<Boolean> bindControlMessageAsync(
            long guildId,
            String sessionId,
            long messageId
    ) {
        return guildExecutor.submit(guildId, () -> {
            DiscordGuildPlaybackSession session = sessionsByGuild.get(guildId);
            return session != null
                    && session.getSessionId().equals(sessionId)
                    && session.bindControlMessage(messageId);
        });
    }

    public CompletableFuture<PlaybackControlResult> controlAsync(
            Guild guild,
            Member requester,
            PlaybackControlRequest request
    ) {
        if (guild == null) {
            return CompletableFuture.completedFuture(result(
                    PlaybackControlStatus.NO_ACTIVE_SESSION,
                    "Não existe uma reprodução ativa nesta comunidade."
            ));
        }
        return guildExecutor.submit(
                guild.getIdLong(),
                () -> controlInGuild(guild, requester, request)
        );
    }

    public PlaybackControlResult control(
            Guild guild,
            Member requester,
            PlaybackControlRequest request
    ) {
        return join(controlAsync(guild, requester, request));
    }

    public CompletableFuture<DiscordVoiceConnectionService.VoiceCommandResult> leaveAsync(
            Guild guild,
            Member requester,
            String interactionId
    ) {
        if (guild == null) {
            return CompletableFuture.completedFuture(
                    DiscordVoiceConnectionService.VoiceCommandResult.BOT_NOT_CONNECTED
            );
        }
        return guildExecutor.submit(guild.getIdLong(), () -> {
            long guildId = guild.getIdLong();
            if (isDuplicateInteraction(guildId, interactionId)) {
                return DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_IN_PROGRESS;
            }
            DiscordVoiceConnectionService.VoiceCommandResult voiceResult = voiceConnectionService.leave(
                    guild,
                    requester,
                    () -> stopForLeaveInGuild(guildId)
            );
            if (voiceResult == DiscordVoiceConnectionService.VoiceCommandResult.BOT_NOT_CONNECTED) {
                stopForLeaveInGuild(guildId);
            }
            releaseGuildMetadataIfInactive(guildId);
            return voiceResult;
        });
    }

    public void stop(Guild guild) {
        if (guild != null) {
            join(guildExecutor.submit(guild.getIdLong(), () -> {
                stopForLeaveInGuild(guild.getIdLong());
                return null;
            }));
        }
    }

    Optional<DiscordGuildPlaybackSession> sessionFor(long guildId) {
        return Optional.ofNullable(sessionsByGuild.get(guildId));
    }

    int activeSessionCount() {
        return sessionsByGuild.size();
    }

    int idleConnectionCount() {
        return idleConnectionsByGuild.size();
    }

    int activeGuildLaneCount() {
        return guildExecutor.activeLaneCount();
    }

    void awaitGuild(long guildId) {
        join(guildExecutor.submit(guildId, () -> null));
    }

    public DiscordGuildPlaybackSession.PlaybackState stateFor(long guildId) {
        DiscordGuildPlaybackSession session = sessionsByGuild.get(guildId);
        return session == null
                ? DiscordGuildPlaybackSession.PlaybackState.IDLE
                : session.getState();
    }

    @PreDestroy
    public void closeAll() {
        Set<Long> guildIds = new LinkedHashSet<>(sessionsByGuild.keySet());
        guildIds.addAll(idleConnectionsByGuild.keySet());
        for (Long guildId : guildIds) {
            try {
                join(guildExecutor.submit(guildId, () -> {
                    closeGuildForShutdown(guildId);
                    return null;
                }));
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not serialize Discord guild shutdown: guildId={}", guildId);
                closeGuildForShutdown(guildId);
            }
        }
        scheduler.shutdownNow();
        audioSetupExecutor.shutdownNow();
        guildExecutor.close();
        recentInteractionsByGuild.clear();
    }

    private DiscordVoiceConnectionService.VoiceCommandResult joinIdleInGuild(
            Guild guild,
            Member requester,
            String interactionId
    ) {
        long guildId = guild.getIdLong();
        if (isDuplicateInteraction(guildId, interactionId)) {
            return DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_IN_PROGRESS;
        }
        VoiceChannel requestedChannel = getStandardVoiceChannel(requester);
        Object ownerToken = new Object();
        DiscordVoiceConnectionService.VoiceCommandResult voiceResult = voiceConnectionService.join(
                guild,
                requester,
                status -> submitVoiceCallback(guildId, () -> {
                    if (isTerminalVoiceStatus(status)) {
                        cancelIdleConnectionInGuild(guildId, ownerToken, status.name());
                    }
                })
        );
        if ((voiceResult == DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED
                || voiceResult == DiscordVoiceConnectionService.VoiceCommandResult.ALREADY_IN_CHANNEL)
                && requestedChannel != null
                && !sessionsByGuild.containsKey(guildId)) {
            scheduleIdleConnectionInGuild(
                    guildId,
                    requestedChannel,
                    guild.getAudioManager(),
                    ownerToken,
                    IdleReason.JOIN_WITHOUT_PLAYBACK
            );
        }
        return voiceResult;
    }

    private StartReservation reserveStartInGuild(
            Guild guild,
            Member requester,
            DiscordTrack track,
            String interactionId,
            Consumer<PlaybackProgress> progressObserver
    ) {
        long guildId = guild.getIdLong();
        if (isDuplicateInteraction(guildId, interactionId)) {
            return rejectedReservation(PlaybackStartResult.DUPLICATE_INTERACTION);
        }
        VoiceChannel requestedChannel = getStandardVoiceChannel(requester);
        if (requestedChannel == null) {
            return rejectedReservation(
                    requesterHasAudioChannel(requester)
                            ? PlaybackStartResult.UNSUPPORTED_CHANNEL
                            : PlaybackStartResult.USER_NOT_IN_VOICE
            );
        }
        if (!guild.getSelfMember().hasPermission(requestedChannel, Permission.VOICE_SPEAK)) {
            return rejectedReservation(PlaybackStartResult.MISSING_PERMISSIONS);
        }
        if (sessionsByGuild.containsKey(guildId)) {
            return rejectedReservation(PlaybackStartResult.ALREADY_PLAYING);
        }

        AudioManager audioManager = guild.getAudioManager();
        DiscordGuildPlaybackSession session = new DiscordGuildPlaybackSession(
                UUID.randomUUID().toString(),
                guildId,
                requestedChannel,
                audioManager,
                track,
                progressObserver
        );
        sessionsByGuild.put(guildId, session);
        DiscordVoiceConnectionService.VoiceCommandResult voiceResult = voiceConnectionService.join(
                guild,
                requester,
                status -> submitVoiceCallback(guildId, () -> handleVoiceStatusInGuild(session, status))
        );
        PlaybackStartResult rejected = mapRejectedVoiceResult(voiceResult);
        if (rejected != null) {
            sessionsByGuild.remove(guildId, session);
            if (rejected == PlaybackStartResult.VOICE_CONNECTION_FAILED) {
                cancelIdleConnectionInGuild(guildId, null, "fatal_voice_start_failure");
            }
            session.close(rejected == PlaybackStartResult.VOICE_CONNECTION_FAILED);
            releaseGuildMetadataIfInactive(guildId);
            LOGGER.info(
                    "Discord playback cleanup: guildId={}, trackId={}, reason={}, detail={}",
                    guildId,
                    track.id(),
                    CleanupReason.VOICE_START_REJECTED,
                    voiceResult.name()
            );
            return rejectedReservation(rejected);
        }

        cancelIdleConnectionInGuild(guildId, null, "valid_playback_request");
        LOGGER.info("Discord playback session created: guildId={}, trackId={}", guildId, track.id());
        return new StartReservation(session, null);
    }

    private CompletableFuture<AudioSourceOpening> openAudioSourceAsync(DiscordGuildPlaybackSession session) {
        try {
            return CompletableFuture.supplyAsync(
                    () -> openAudioSource(session),
                    audioSetupExecutor
            );
        } catch (RejectedExecutionException failure) {
            return CompletableFuture.completedFuture(AudioSourceOpening.failed(failure));
        }
    }

    private AudioSourceOpening openAudioSource(DiscordGuildPlaybackSession session) {
        AtomicBoolean attached = new AtomicBoolean();
        AtomicBoolean firstFrameObserved = new AtomicBoolean();
        AtomicBoolean terminationObserved = new AtomicBoolean();
        try {
            DiscordAudioSource source = audioSourceFactory.open(
                    session.getCurrentTrack(),
                    () -> {
                        firstFrameObserved.set(true);
                        if (attached.get()) {
                            submitSessionCallback(session, () -> markPlayingInGuild(session));
                        }
                    },
                    () -> {
                        terminationObserved.set(true);
                        if (attached.get()) {
                            submitSessionCallback(session, () -> handleAudioSourceTerminationInGuild(session));
                        }
                    }
            );
            return AudioSourceOpening.opened(
                    source,
                    attached,
                    firstFrameObserved,
                    terminationObserved
            );
        } catch (IOException | RuntimeException failure) {
            return AudioSourceOpening.failed(failure);
        }
    }

    private PlaybackStartOutcome completeStartInGuild(
            DiscordGuildPlaybackSession session,
            AudioSourceOpening opening
    ) {
        if (opening.failure() != null) {
            removeAndCloseInGuild(session, true, CleanupReason.AUDIO_SOURCE_START_FAILED, null);
            return outcome(PlaybackStartResult.AUDIO_SOURCE_FAILED, null);
        }
        DiscordAudioSource source = opening.source();
        if (sessionsByGuild.get(session.getGuildId()) != session
                || session.getState() == DiscordGuildPlaybackSession.PlaybackState.STOPPING) {
            source.close();
            return outcome(PlaybackStartResult.VOICE_CONNECTION_FAILED, null);
        }
        try {
            session.attach(source);
            session.getVoiceConnection().setSendingHandler(source);
            session.setStartupTimeout(scheduler.schedule(
                    () -> submitSessionCallback(session, () -> removeAndCloseInGuild(
                            session,
                            true,
                            CleanupReason.PLAYBACK_START_TIMEOUT,
                            null
                    )),
                    startupTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
            ));
            opening.attached().set(true);
            if (opening.terminationObserved().get()) {
                handleAudioSourceTerminationInGuild(session);
                return outcome(PlaybackStartResult.AUDIO_SOURCE_FAILED, null);
            }
            if (opening.firstFrameObserved().get()) {
                markPlayingInGuild(session);
            }
            return outcome(PlaybackStartResult.STARTED, session.getSessionId());
        } catch (RuntimeException failure) {
            opening.attached().set(true);
            removeAndCloseInGuild(session, true, CleanupReason.AUDIO_SOURCE_START_FAILED, null);
            return outcome(PlaybackStartResult.AUDIO_SOURCE_FAILED, null);
        }
    }

    private PlaybackControlResult controlInGuild(
            Guild guild,
            Member requester,
            PlaybackControlRequest request
    ) {
        long guildId = guild.getIdLong();
        if (isDuplicateInteraction(guildId, request.interactionId())) {
            return result(PlaybackControlStatus.DUPLICATE, "Esse controle já foi processado.");
        }
        DiscordGuildPlaybackSession session = sessionsByGuild.get(guildId);
        if (session == null) {
            releaseGuildMetadataIfInactive(guildId);
            return result(PlaybackControlStatus.NO_ACTIVE_SESSION, "Não existe uma reprodução ativa nesta comunidade.");
        }
        if (request.expectedSessionId() != null
                && !request.expectedSessionId().equals(session.getSessionId())) {
            return result(PlaybackControlStatus.EXPIRED_CONTROL, "Esse controle pertence a uma reprodução encerrada.");
        }
        if (request.expectedMessageId() != null
                && !session.matchesControlMessage(request.expectedMessageId())) {
            return result(PlaybackControlStatus.EXPIRED_CONTROL, "Essa mensagem de controle não está mais ativa.");
        }

        PlaybackControlResult authorization = authorizeController(session, requester);
        if (authorization != null) {
            return authorization;
        }

        return switch (request.action()) {
            case PAUSE -> pauseInGuild(session);
            case RESUME -> resumeInGuild(session);
            case STOP -> stopAndKeepVoiceInGuild(session);
        };
    }

    private PlaybackControlResult pauseInGuild(DiscordGuildPlaybackSession session) {
        return switch (session.getState()) {
            case PAUSED -> result(PlaybackControlStatus.ALREADY_APPLIED, "A reprodução já está pausada.");
            case STARTING -> result(PlaybackControlStatus.NOT_READY, "A faixa ainda está sendo preparada.");
            case PLAYING -> {
                try {
                    if (!session.pause()) {
                        yield result(PlaybackControlStatus.NOT_READY, "A reprodução ainda não pode ser pausada.");
                    }
                    Object pauseGeneration = session.beginPauseTimeoutGeneration();
                    ScheduledFuture<?> pauseTimeout = scheduler.schedule(
                            () -> submitSessionCallback(
                                    session,
                                    () -> expirePausedSessionInGuild(session, pauseGeneration)
                            ),
                            inactivityTimeout.toMillis(),
                            TimeUnit.MILLISECONDS
                    );
                    session.setPauseTimeout(pauseGeneration, pauseTimeout);
                    session.notifyProgress(PlaybackProgress.PAUSED);
                    yield result(PlaybackControlStatus.APPLIED, "Reprodução pausada.");
                } catch (RuntimeException failure) {
                    removeAndCloseInGuild(session, true, CleanupReason.CONTROL_FAILURE, "pause");
                    yield result(PlaybackControlStatus.FAILED, "Não consegui pausar; a sessão foi encerrada com segurança.");
                }
            }
            default -> result(PlaybackControlStatus.NO_ACTIVE_SESSION, "Não existe uma reprodução ativa para pausar.");
        };
    }

    private PlaybackControlResult resumeInGuild(DiscordGuildPlaybackSession session) {
        return switch (session.getState()) {
            case PLAYING -> result(PlaybackControlStatus.ALREADY_APPLIED, "A reprodução já está em andamento.");
            case PAUSED -> {
                try {
                    if (!session.resumePlayback()) {
                        yield result(PlaybackControlStatus.NOT_READY, "A reprodução não pode ser retomada agora.");
                    }
                    session.notifyProgress(PlaybackProgress.RESUMED);
                    yield result(PlaybackControlStatus.APPLIED, "Reprodução retomada.");
                } catch (RuntimeException failure) {
                    removeAndCloseInGuild(session, true, CleanupReason.CONTROL_FAILURE, "resume");
                    yield result(PlaybackControlStatus.FAILED, "Não consegui retomar; a sessão foi encerrada com segurança.");
                }
            }
            case STARTING -> result(PlaybackControlStatus.NOT_READY, "A faixa ainda está sendo preparada.");
            default -> result(PlaybackControlStatus.NO_ACTIVE_SESSION, "Não existe uma reprodução pausada para retomar.");
        };
    }

    private PlaybackControlResult stopAndKeepVoiceInGuild(DiscordGuildPlaybackSession session) {
        if (!sessionsByGuild.remove(session.getGuildId(), session)) {
            return result(PlaybackControlStatus.NO_ACTIVE_SESSION, "A reprodução já foi encerrada.");
        }
        session.beginStopping();
        session.close(false);
        scheduleIdleConnectionInGuild(
                session.getGuildId(),
                session.getVoiceChannel(),
                session.getVoiceConnection(),
                session,
                IdleReason.USER_STOPPED_TRACK
        );
        session.notifyProgress(PlaybackProgress.STOPPED);
        LOGGER.info(
                "Discord playback cleanup: guildId={}, trackId={}, reason={}",
                session.getGuildId(),
                session.getCurrentTrack().id(),
                CleanupReason.USER_REQUESTED_STOP
        );
        return result(PlaybackControlStatus.APPLIED, "Reprodução encerrada.");
    }

    private PlaybackControlResult authorizeController(DiscordGuildPlaybackSession session, Member requester) {
        if (requester == null || requester.getVoiceState() == null
                || requester.getVoiceState().getChannel() == null) {
            return result(PlaybackControlStatus.USER_NOT_IN_VOICE, "Entre no canal de voz do bot para controlar a reprodução.");
        }
        AudioChannelUnion requesterChannel = requester.getVoiceState().getChannel();
        if (requesterChannel.getType() != ChannelType.VOICE) {
            return result(PlaybackControlStatus.UNSUPPORTED_CHANNEL, "Os controles funcionam somente em canais de voz comuns.");
        }

        AudioChannelUnion botChannel;
        try {
            botChannel = session.getVoiceConnection().getConnectedChannel();
        } catch (RuntimeException failure) {
            return result(PlaybackControlStatus.FAILED, "Não consegui confirmar o canal de voz do bot agora.");
        }
        if (botChannel == null) {
            return result(PlaybackControlStatus.NOT_READY, "A conexão de voz do bot ainda não está pronta.");
        }
        if (botChannel.getType() != ChannelType.VOICE
                || botChannel.getIdLong() != session.getVoiceChannel().getIdLong()) {
            return result(PlaybackControlStatus.EXPIRED_CONTROL, "A sessão não está mais no canal de voz original.");
        }
        if (requesterChannel.getIdLong() != botChannel.getIdLong()) {
            return result(PlaybackControlStatus.DIFFERENT_CHANNEL, "Você precisa estar no mesmo canal de voz do bot.");
        }
        return null;
    }

    private void markPlayingInGuild(DiscordGuildPlaybackSession session) {
        if (sessionsByGuild.get(session.getGuildId()) == session && session.markPlaying()) {
            LOGGER.info(
                    "Discord playback first frame delivered: guildId={}, trackId={}",
                    session.getGuildId(),
                    session.getCurrentTrack().id()
            );
            session.notifyProgress(PlaybackProgress.AUDIO_FRAMES_AVAILABLE);
        }
    }

    private void expirePausedSessionInGuild(
            DiscordGuildPlaybackSession session,
            Object pauseGeneration
    ) {
        if (sessionsByGuild.get(session.getGuildId()) != session
                || !session.matchesPauseTimeout(pauseGeneration)
                || !sessionsByGuild.remove(session.getGuildId(), session)) {
            return;
        }
        session.beginStopping();
        session.close(true);
        session.notifyProgress(PlaybackProgress.PAUSE_TIMEOUT);
        releaseGuildMetadataIfInactive(session.getGuildId());
        LOGGER.info(
                "Discord playback cleanup: guildId={}, trackId={}, reason={}",
                session.getGuildId(),
                session.getCurrentTrack().id(),
                CleanupReason.PAUSE_TIMEOUT
        );
    }

    private void removeAndCloseInGuild(
            DiscordGuildPlaybackSession session,
            boolean closeVoiceConnection,
            CleanupReason reason,
            String detail
    ) {
        if (!sessionsByGuild.remove(session.getGuildId(), session)) {
            return;
        }
        cancelIdleConnectionInGuild(session.getGuildId(), null, "terminal_playback_cleanup");
        session.beginStopping();
        session.close(closeVoiceConnection);
        LOGGER.info(
                "Discord playback cleanup: guildId={}, trackId={}, reason={}, detail={}",
                session.getGuildId(),
                session.getCurrentTrack().id(),
                reason,
                detail == null ? "none" : detail
        );
        notifyCleanupProgress(session, reason);
        releaseGuildMetadataIfInactive(session.getGuildId());
    }

    private void handleAudioSourceTerminationInGuild(DiscordGuildPlaybackSession session) {
        if (!sessionsByGuild.remove(session.getGuildId(), session)) {
            return;
        }
        DiscordGuildPlaybackSession.PlaybackState previousState = session.getState();
        boolean completed = previousState == DiscordGuildPlaybackSession.PlaybackState.PLAYING
                || previousState == DiscordGuildPlaybackSession.PlaybackState.PAUSED;
        session.beginStopping();
        session.close(!completed);
        if (completed) {
            scheduleIdleConnectionInGuild(
                    session.getGuildId(),
                    session.getVoiceChannel(),
                    session.getVoiceConnection(),
                    session,
                    IdleReason.NATURAL_TRACK_END
            );
        }
        LOGGER.info(
                "Discord playback cleanup: guildId={}, trackId={}, reason={}, detail=none",
                session.getGuildId(),
                session.getCurrentTrack().id(),
                CleanupReason.AUDIO_SOURCE_TERMINATED
        );
        session.notifyProgress(completed ? PlaybackProgress.COMPLETED : PlaybackProgress.FAILED);
        releaseGuildMetadataIfInactive(session.getGuildId());
    }

    private void handleVoiceStatusInGuild(DiscordGuildPlaybackSession session, ConnectionStatus status) {
        if (status == ConnectionStatus.CONNECTED && sessionsByGuild.get(session.getGuildId()) == session) {
            session.notifyProgress(PlaybackProgress.VOICE_CONNECTED);
        }
        if (!isTerminalVoiceStatus(status)) {
            return;
        }
        if (sessionsByGuild.get(session.getGuildId()) == session) {
            removeAndCloseInGuild(
                    session,
                    true,
                    CleanupReason.VOICE_TERMINAL_STATUS,
                    status.name()
            );
        } else {
            cancelIdleConnectionInGuild(session.getGuildId(), session, status.name());
            releaseGuildMetadataIfInactive(session.getGuildId());
        }
    }

    private void scheduleIdleConnectionInGuild(
            long guildId,
            VoiceChannel voiceChannel,
            AudioManager audioManager,
            Object ownerToken,
            IdleReason reason
    ) {
        if (sessionsByGuild.containsKey(guildId)) {
            return;
        }
        cancelIdleConnectionInGuild(guildId, null, "idle_timer_replaced");
        IdleVoiceConnection idleConnection = new IdleVoiceConnection(
                guildId,
                voiceChannel.getIdLong(),
                audioManager,
                ownerToken
        );
        idleConnectionsByGuild.put(guildId, idleConnection);
        idleConnection.setTimeout(scheduler.schedule(
                () -> submitVoiceCallback(guildId, () -> disconnectIfStillIdleInGuild(idleConnection)),
                inactivityTimeout.toMillis(),
                TimeUnit.MILLISECONDS
        ));
        LOGGER.info(
                "Discord voice idle timer scheduled: guildId={}, channelId={}, reason={}, timeoutSeconds={}",
                guildId,
                voiceChannel.getIdLong(),
                reason,
                inactivityTimeout.toSeconds()
        );
    }

    private void disconnectIfStillIdleInGuild(IdleVoiceConnection idleConnection) {
        if (idleConnectionsByGuild.get(idleConnection.guildId()) != idleConnection
                || sessionsByGuild.containsKey(idleConnection.guildId())
                || !idleConnectionsByGuild.remove(idleConnection.guildId(), idleConnection)) {
            return;
        }
        closeIdleVoiceConnection(idleConnection, IdleReason.IDLE_TIMEOUT);
        releaseGuildMetadataIfInactive(idleConnection.guildId());
    }

    private void cancelIdleConnectionInGuild(long guildId, Object ownerToken, String detail) {
        IdleVoiceConnection idleConnection = idleConnectionsByGuild.get(guildId);
        if (idleConnection == null
                || (ownerToken != null && idleConnection.ownerToken() != ownerToken)
                || !idleConnectionsByGuild.remove(guildId, idleConnection)) {
            return;
        }
        idleConnection.cancelTimeout();
        LOGGER.info(
                "Discord voice idle timer cancelled: guildId={}, channelId={}, detail={}",
                guildId,
                idleConnection.channelId(),
                detail
        );
    }

    private void closeIdleVoiceConnection(IdleVoiceConnection idleConnection, IdleReason reason) {
        idleConnection.cancelTimeout();
        try {
            idleConnection.audioManager().closeAudioConnection();
            LOGGER.info(
                    "Discord idle voice connection closed: guildId={}, channelId={}, reason={}",
                    idleConnection.guildId(),
                    idleConnection.channelId(),
                    reason
            );
        } catch (RuntimeException ignored) {
            LOGGER.warn(
                    "Could not close idle Discord voice connection: guildId={}, channelId={}, reason={}",
                    idleConnection.guildId(),
                    idleConnection.channelId(),
                    reason
            );
        }
    }

    private void stopForLeaveInGuild(long guildId) {
        DiscordGuildPlaybackSession session = sessionsByGuild.remove(guildId);
        cancelIdleConnectionInGuild(guildId, null, "user_requested_leave");
        if (session != null) {
            session.beginStopping();
            session.close(false);
            session.notifyProgress(PlaybackProgress.STOPPED);
            LOGGER.info(
                    "Discord playback cleanup: guildId={}, trackId={}, reason={}",
                    guildId,
                    session.getCurrentTrack().id(),
                    CleanupReason.USER_REQUESTED_LEAVE
            );
        }
    }

    private void closeGuildForShutdown(long guildId) {
        DiscordGuildPlaybackSession session = sessionsByGuild.remove(guildId);
        if (session != null) {
            session.beginStopping();
            session.close(true);
            LOGGER.info(
                    "Discord playback cleanup: guildId={}, trackId={}, reason={}",
                    guildId,
                    session.getCurrentTrack().id(),
                    CleanupReason.APPLICATION_SHUTDOWN
            );
        }
        IdleVoiceConnection idle = idleConnectionsByGuild.remove(guildId);
        if (idle != null) {
            closeIdleVoiceConnection(idle, IdleReason.APPLICATION_SHUTDOWN);
        }
        recentInteractionsByGuild.remove(guildId);
    }

    private void notifyCleanupProgress(DiscordGuildPlaybackSession session, CleanupReason reason) {
        if (reason == CleanupReason.APPLICATION_SHUTDOWN
                || reason == CleanupReason.VOICE_START_REJECTED) {
            return;
        }
        session.notifyProgress(reason == CleanupReason.PLAYBACK_START_TIMEOUT
                ? PlaybackProgress.START_TIMEOUT
                : PlaybackProgress.FAILED);
    }

    private void submitSessionCallback(DiscordGuildPlaybackSession session, Runnable callback) {
        guildExecutor.submit(session.getGuildId(), () -> {
            callback.run();
            return null;
        }).exceptionally(failure -> {
            LOGGER.warn("Could not serialize Discord playback callback: guildId={}", session.getGuildId());
            return null;
        });
    }

    private void submitVoiceCallback(long guildId, Runnable callback) {
        guildExecutor.submit(guildId, () -> {
            callback.run();
            return null;
        }).exceptionally(failure -> {
            LOGGER.warn("Could not serialize Discord voice callback: guildId={}", guildId);
            return null;
        });
    }

    private boolean isDuplicateInteraction(long guildId, String interactionId) {
        if (interactionId == null || interactionId.isBlank()) {
            return false;
        }
        LinkedHashMap<String, Boolean> interactions = recentInteractionsByGuild.computeIfAbsent(
                guildId,
                ignored -> new LinkedHashMap<>()
        );
        if (interactions.containsKey(interactionId)) {
            return true;
        }
        interactions.put(interactionId, Boolean.TRUE);
        while (interactions.size() > INTERACTION_DEDUPLICATION_LIMIT) {
            String oldest = interactions.keySet().iterator().next();
            interactions.remove(oldest);
        }
        return false;
    }

    private void releaseGuildMetadataIfInactive(long guildId) {
        if (!sessionsByGuild.containsKey(guildId) && !idleConnectionsByGuild.containsKey(guildId)) {
            recentInteractionsByGuild.remove(guildId);
        }
    }

    private boolean isTerminalVoiceStatus(ConnectionStatus status) {
        String name = status.name();
        return status == ConnectionStatus.NOT_CONNECTED
                || status == ConnectionStatus.SHUTTING_DOWN
                || name.startsWith("DISCONNECTED_")
                || name.startsWith("ERROR_");
    }

    private VoiceChannel getStandardVoiceChannel(Member requester) {
        if (requester == null) {
            return null;
        }
        GuildVoiceState voiceState = requester.getVoiceState();
        if (voiceState == null || voiceState.getChannel() == null) {
            return null;
        }
        AudioChannelUnion channel = voiceState.getChannel();
        return channel.getType() == ChannelType.VOICE ? channel.asVoiceChannel() : null;
    }

    private boolean requesterHasAudioChannel(Member requester) {
        return requester != null
                && requester.getVoiceState() != null
                && requester.getVoiceState().getChannel() != null;
    }

    private PlaybackStartResult mapRejectedVoiceResult(
            DiscordVoiceConnectionService.VoiceCommandResult voiceResult
    ) {
        return switch (voiceResult) {
            case CONNECTION_STARTED, ALREADY_IN_CHANNEL -> null;
            case CONNECTION_IN_PROGRESS -> PlaybackStartResult.VOICE_CONNECTION_IN_PROGRESS;
            case USER_NOT_IN_VOICE -> PlaybackStartResult.USER_NOT_IN_VOICE;
            case UNSUPPORTED_CHANNEL -> PlaybackStartResult.UNSUPPORTED_CHANNEL;
            case MISSING_PERMISSIONS -> PlaybackStartResult.MISSING_PERMISSIONS;
            case ALREADY_IN_ANOTHER_CHANNEL, CONNECTION_IN_PROGRESS_ELSEWHERE ->
                    PlaybackStartResult.BOT_IN_ANOTHER_CHANNEL;
            default -> PlaybackStartResult.VOICE_CONNECTION_FAILED;
        };
    }

    private PlaybackStartOutcome outcome(PlaybackStartResult result, String sessionId) {
        return new PlaybackStartOutcome(result, sessionId);
    }

    private StartReservation rejectedReservation(PlaybackStartResult result) {
        return new StartReservation(null, outcome(result, null));
    }

    private ExecutorService newAudioSetupExecutor() {
        int workers = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 4));
        return new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32),
                runnable -> Thread.ofPlatform()
                        .daemon(true)
                        .name("deefy-discord-audio-setup")
                        .unstarted(runnable),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private PlaybackControlResult result(PlaybackControlStatus status, String message) {
        return new PlaybackControlResult(status, message);
    }

    private <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw failure;
        }
    }

    public record PlaybackStartOutcome(PlaybackStartResult result, String sessionId) {
    }

    private record StartReservation(
            DiscordGuildPlaybackSession session,
            PlaybackStartOutcome outcome
    ) {
    }

    private record AudioSourceOpening(
            DiscordAudioSource source,
            AtomicBoolean attached,
            AtomicBoolean firstFrameObserved,
            AtomicBoolean terminationObserved,
            Throwable failure
    ) {

        private static AudioSourceOpening opened(
                DiscordAudioSource source,
                AtomicBoolean attached,
                AtomicBoolean firstFrameObserved,
                AtomicBoolean terminationObserved
        ) {
            return new AudioSourceOpening(
                    source,
                    attached,
                    firstFrameObserved,
                    terminationObserved,
                    null
            );
        }

        private static AudioSourceOpening failed(Throwable failure) {
            return new AudioSourceOpening(
                    null,
                    new AtomicBoolean(),
                    new AtomicBoolean(),
                    new AtomicBoolean(),
                    failure
            );
        }
    }

    public record PlaybackControlRequest(
            PlaybackControlAction action,
            String expectedSessionId,
            Long expectedMessageId,
            String interactionId
    ) {

        public PlaybackControlRequest {
            if (action == null) {
                throw new IllegalArgumentException("action is required");
            }
        }

        public static PlaybackControlRequest slash(PlaybackControlAction action, String interactionId) {
            return new PlaybackControlRequest(action, null, null, interactionId);
        }

        public static PlaybackControlRequest button(
                PlaybackControlAction action,
                String sessionId,
                long messageId,
                String interactionId
        ) {
            return new PlaybackControlRequest(action, sessionId, messageId, interactionId);
        }
    }

    public record PlaybackControlResult(PlaybackControlStatus status, String responseMessage) {

        public boolean applied() {
            return status == PlaybackControlStatus.APPLIED
                    || status == PlaybackControlStatus.ALREADY_APPLIED;
        }
    }

    public enum PlaybackControlAction {
        PAUSE,
        RESUME,
        STOP
    }

    public enum PlaybackControlStatus {
        APPLIED,
        ALREADY_APPLIED,
        NOT_READY,
        NO_ACTIVE_SESSION,
        EXPIRED_CONTROL,
        USER_NOT_IN_VOICE,
        UNSUPPORTED_CHANNEL,
        DIFFERENT_CHANNEL,
        DUPLICATE,
        FAILED
    }

    public enum PlaybackStartResult {
        STARTED,
        ALREADY_PLAYING,
        DUPLICATE_INTERACTION,
        USER_NOT_IN_VOICE,
        UNSUPPORTED_CHANNEL,
        MISSING_PERMISSIONS,
        BOT_IN_ANOTHER_CHANNEL,
        VOICE_CONNECTION_IN_PROGRESS,
        VOICE_CONNECTION_FAILED,
        AUDIO_SOURCE_FAILED
    }

    private enum CleanupReason {
        VOICE_TERMINAL_STATUS,
        VOICE_START_REJECTED,
        AUDIO_SOURCE_TERMINATED,
        AUDIO_SOURCE_START_FAILED,
        PLAYBACK_START_TIMEOUT,
        PAUSE_TIMEOUT,
        CONTROL_FAILURE,
        USER_REQUESTED_STOP,
        USER_REQUESTED_LEAVE,
        APPLICATION_SHUTDOWN
    }

    public enum PlaybackProgress {
        VOICE_CONNECTED,
        AUDIO_FRAMES_AVAILABLE,
        PAUSED,
        RESUMED,
        STOPPED,
        PAUSE_TIMEOUT,
        START_TIMEOUT,
        FAILED,
        COMPLETED
    }

    private enum IdleReason {
        JOIN_WITHOUT_PLAYBACK,
        NATURAL_TRACK_END,
        USER_STOPPED_TRACK,
        IDLE_TIMEOUT,
        APPLICATION_SHUTDOWN
    }

    private static final class IdleVoiceConnection {

        private final long guildId;
        private final long channelId;
        private final AudioManager audioManager;
        private final Object ownerToken;
        private ScheduledFuture<?> timeout;

        private IdleVoiceConnection(
                long guildId,
                long channelId,
                AudioManager audioManager,
                Object ownerToken
        ) {
            this.guildId = guildId;
            this.channelId = channelId;
            this.audioManager = audioManager;
            this.ownerToken = ownerToken;
        }

        private long guildId() {
            return guildId;
        }

        private long channelId() {
            return channelId;
        }

        private AudioManager audioManager() {
            return audioManager;
        }

        private Object ownerToken() {
            return ownerToken;
        }

        private synchronized void setTimeout(ScheduledFuture<?> timeout) {
            this.timeout = timeout;
        }

        private synchronized void cancelTimeout() {
            if (timeout != null) {
                timeout.cancel(false);
                timeout = null;
            }
        }
    }
}
