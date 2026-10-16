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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

@Service
public class DiscordPlaybackSessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordPlaybackSessionService.class);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration INACTIVITY_TIMEOUT = Duration.ofMinutes(10);
    private static final int INTERACTION_DEDUPLICATION_LIMIT = 128;

    private final DiscordVoiceConnectionService voiceConnectionService;
    private final DiscordAudioSourceFactory audioSourceFactory;
    private final DiscordGuildCommandExecutor guildExecutor;
    private final DiscordAudioDownloadManager audioDownloadManager;
    private final DiscordPlaybackProperties playbackProperties;
    private final ExecutorService audioSetupExecutor;
    private final ScheduledExecutorService scheduler;
    private final Duration startupTimeout;
    private final Duration inactivityTimeout;
    private final ConcurrentMap<Long, DiscordGuildPlaybackSession> sessionsByGuild = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, GuildQueueState> queuesByGuild = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, IdleVoiceConnection> idleConnectionsByGuild = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, LinkedHashMap<String, Boolean>> recentInteractionsByGuild =
            new ConcurrentHashMap<>();

    @Autowired
    public DiscordPlaybackSessionService(
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordAudioSourceFactory audioSourceFactory,
            DiscordGuildCommandExecutor guildExecutor,
            DiscordAudioDownloadManager audioDownloadManager,
            DiscordPlaybackProperties playbackProperties
    ) {
        this(
                voiceConnectionService,
                audioSourceFactory,
                guildExecutor,
                Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform()
                        .daemon(true)
                        .name("deefy-discord-playback-timeout")
                        .unstarted(runnable)),
                playbackProperties.getAudioInitialBufferTimeout(),
                INACTIVITY_TIMEOUT,
                audioDownloadManager,
                playbackProperties
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
            DiscordGuildCommandExecutor guildExecutor
    ) {
        this(
                voiceConnectionService,
                audioSourceFactory,
                guildExecutor,
                Executors.newSingleThreadScheduledExecutor(),
                STARTUP_TIMEOUT,
                INACTIVITY_TIMEOUT,
                null,
                new DiscordPlaybackProperties()
        );
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
                INACTIVITY_TIMEOUT,
                null,
                new DiscordPlaybackProperties()
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
                inactivityTimeout,
                null,
                new DiscordPlaybackProperties()
        );
    }

    DiscordPlaybackSessionService(
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordAudioSourceFactory audioSourceFactory,
            DiscordGuildCommandExecutor guildExecutor,
            ScheduledExecutorService scheduler,
            Duration startupTimeout,
            Duration inactivityTimeout,
            DiscordAudioDownloadManager audioDownloadManager,
            DiscordPlaybackProperties playbackProperties
    ) {
        this.voiceConnectionService = voiceConnectionService;
        this.audioSourceFactory = audioSourceFactory;
        this.guildExecutor = guildExecutor;
        this.audioDownloadManager = audioDownloadManager;
        this.playbackProperties = playbackProperties;
        this.audioSetupExecutor = newAudioSetupExecutor();
        this.scheduler = scheduler;
        this.startupTimeout = startupTimeout;
        this.inactivityTimeout = inactivityTimeout;
    }

    DiscordPlaybackSessionService(
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordAudioSourceFactory audioSourceFactory,
            DiscordGuildCommandExecutor guildExecutor,
            ScheduledExecutorService scheduler,
            Duration startupTimeout,
            Duration inactivityTimeout
    ) {
        this(
                voiceConnectionService,
                audioSourceFactory,
                guildExecutor,
                scheduler,
                startupTimeout,
                inactivityTimeout,
                null,
                new DiscordPlaybackProperties()
        );
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
        return startAsyncInternal(
                guild,
                requester,
                track,
                interactionId,
                (ignoredSessionId, progress) -> progressObserver.accept(progress)
        );
    }

    CompletableFuture<PlaybackStartOutcome> startAsyncWithSession(
            Guild guild,
            Member requester,
            DiscordTrack track,
            String interactionId,
            BiConsumer<String, PlaybackProgress> progressObserver
    ) {
        return startAsyncInternal(guild, requester, track, interactionId, progressObserver);
    }

    private CompletableFuture<PlaybackStartOutcome> startAsyncInternal(
            Guild guild,
            Member requester,
            DiscordTrack track,
            String interactionId,
            BiConsumer<String, PlaybackProgress> progressObserver
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
            return prepareAudioAsync(reservation.session())
                    .thenCompose(prepared -> openAudioSourceAsync(reservation.session(), prepared))
                    .thenCompose(opening -> guildExecutor.submit(
                            guild.getIdLong(),
                            () -> completeStartInGuild(reservation.session(), opening)
                    ))
                    .exceptionallyCompose(failure -> guildExecutor.submit(
                            guild.getIdLong(),
                            () -> failPreparationInGuild(reservation.session(), failure)
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
            boolean bound = session != null
                    && session.getSessionId().equals(sessionId)
                    && session.bindControlMessage(messageId);
            GuildQueueState queue = queuesByGuild.get(guildId);
            if (bound && queue != null) {
                queue.setControlMessageId(messageId);
            }
            LOGGER.info(
                    "Discord playback message binding: guildId={}, sessionRef={}, messageRef={}, result={}",
                    guildId,
                    DiscordDiagnosticSupport.pseudonymize(sessionId),
                    DiscordDiagnosticSupport.pseudonymize(messageId),
                    bound ? "accepted" : "rejected"
            );
            return bound;
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

    public CompletableFuture<QueueCommandResult> queueAsync(
            Guild guild,
            Member requester,
            QueueCommandRequest request
    ) {
        if (guild == null) {
            return CompletableFuture.completedFuture(new QueueCommandResult(
                    QueueCommandStatus.NO_ACTIVE_SESSION,
                    "Não existe uma fila nesta comunidade.",
                    QueueSnapshot.empty()
            ));
        }
        return guildExecutor.submit(
                guild.getIdLong(),
                () -> queueInGuild(guild, requester, request)
        );
    }

    private QueueCommandResult queueInGuild(
            Guild guild,
            Member requester,
            QueueCommandRequest request
    ) {
        long guildId = guild.getIdLong();
        if (isDuplicateInteraction(guildId, request.interactionId())) {
            return new QueueCommandResult(
                    QueueCommandStatus.DUPLICATE,
                    "Esse comando de fila já foi processado.",
                    queueSnapshot(guildId)
            );
        }
        GuildQueueState queue = queuesByGuild.get(guildId);
        if (request.action() == QueueAction.VIEW) {
            return new QueueCommandResult(
                    QueueCommandStatus.APPLIED,
                    queue == null || queue.queue().current() == null
                            ? "Não há uma reprodução ou faixa pendente nesta comunidade."
                            : "Fila carregada.",
                    queueSnapshot(guildId)
            );
        }
        DiscordGuildPlaybackSession session = sessionsByGuild.get(guildId);
        if (queue == null || session == null) {
            return new QueueCommandResult(
                    QueueCommandStatus.NO_ACTIVE_SESSION,
                    "Não existe uma reprodução ativa nesta comunidade.",
                    queueSnapshot(guildId)
            );
        }
        PlaybackControlResult authorization = authorizeController(session, requester);
        if (authorization != null) {
            return new QueueCommandResult(
                    QueueCommandStatus.REJECTED,
                    authorization.responseMessage(),
                    queue.snapshot()
            );
        }
        boolean applied;
        String message;
        switch (request.action()) {
            case REMOVE -> {
                applied = queue.queue().remove(request.origin()).isPresent();
                message = applied ? "Faixa removida da fila." : "Essa posição não existe na fila pendente.";
            }
            case MOVE -> {
                applied = queue.queue().move(request.origin(), request.destination());
                message = applied ? "Fila reorganizada." : "As posições informadas não são válidas.";
            }
            case CLEAR -> {
                queue.queue().clearPending();
                applied = true;
                message = "As faixas pendentes foram removidas da fila.";
            }
            case SHUFFLE -> {
                queue.queue().shuffle(ThreadLocalRandom.current());
                applied = true;
                message = "A fila pendente foi embaralhada.";
            }
            case VIEW -> throw new IllegalStateException("VIEW is handled before authorization");
            default -> throw new IllegalStateException("Unsupported queue action");
        }
        return new QueueCommandResult(
                applied ? QueueCommandStatus.APPLIED : QueueCommandStatus.INVALID_POSITION,
                message,
                queue.snapshot()
        );
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

    QueueSnapshot queueSnapshot(long guildId) {
        GuildQueueState state = queuesByGuild.get(guildId);
        if (state == null) {
            return QueueSnapshot.empty();
        }
        return state.snapshot();
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
        queuesByGuild.clear();
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
            BiConsumer<String, PlaybackProgress> progressObserver
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
        PlaybackItem item = new PlaybackItem(
                track,
                progressObserver,
                false,
                requester == null ? "Usuário" : requester.getEffectiveName(),
                requestedChannel.getName()
        );
        DiscordGuildPlaybackSession activeSession = sessionsByGuild.get(guildId);
        if (activeSession != null) {
            if (activeSession.getVoiceChannel().getIdLong() != requestedChannel.getIdLong()) {
                return rejectedReservation(PlaybackStartResult.BOT_IN_ANOTHER_CHANNEL);
            }
            GuildQueueState queue = queuesByGuild.computeIfAbsent(
                    guildId,
                    ignored -> new GuildQueueState(playbackProperties.getQueueMaxSize(), playbackProperties.getHistoryMaxSize())
            );
            if (!queue.queue().enqueue(item)) {
                return rejectedReservation(PlaybackStartResult.QUEUE_FULL);
            }
            return new StartReservation(
                    null,
                    new PlaybackStartOutcome(
                            PlaybackStartResult.QUEUED,
                            activeSession.getSessionId(),
                            queue.queue().pendingSize(),
                            queue.queue().pendingSize()
                    )
            );
        }

        AudioManager audioManager = guild.getAudioManager();
        GuildQueueState queue = queuesByGuild.computeIfAbsent(
                guildId,
                ignored -> new GuildQueueState(playbackProperties.getQueueMaxSize(), playbackProperties.getHistoryMaxSize())
        );
        queue.queue().setCurrent(item);
        DiscordGuildPlaybackSession session = new DiscordGuildPlaybackSession(
                UUID.randomUUID().toString(),
                guildId,
                requestedChannel,
                audioManager,
                track,
                false,
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
            queuesByGuild.remove(guildId, queue);
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
        LOGGER.info(
                "Discord playback session created: guildId={}, sessionRef={}, trackId={}",
                guildId,
                DiscordDiagnosticSupport.pseudonymize(session.getSessionId()),
                track.id()
        );
        return new StartReservation(session, null);
    }

    private CompletableFuture<DiscordPreparedAudio> prepareAudioAsync(DiscordGuildPlaybackSession session) {
        if (audioDownloadManager == null) {
            return CompletableFuture.completedFuture(null);
        }
        return audioDownloadManager.prepare(session.getCurrentTrack());
    }

    private CompletableFuture<AudioSourceOpening> openAudioSourceAsync(
            DiscordGuildPlaybackSession session,
            DiscordPreparedAudio preparedAudio
    ) {
        try {
            return CompletableFuture.supplyAsync(
                    () -> openAudioSource(session, preparedAudio),
                    audioSetupExecutor
            );
        } catch (RejectedExecutionException failure) {
            return CompletableFuture.completedFuture(AudioSourceOpening.failed(failure));
        }
    }

    private AudioSourceOpening openAudioSource(
            DiscordGuildPlaybackSession session,
            DiscordPreparedAudio preparedAudio
    ) {
        AtomicBoolean attached = new AtomicBoolean();
        AtomicBoolean firstFrameObserved = new AtomicBoolean();
        AtomicBoolean terminationObserved = new AtomicBoolean();
        try {
            DiscordAudioSource source;
            Runnable onFirstFrame = () -> {
                        firstFrameObserved.set(true);
                        if (attached.get()) {
                            submitSessionCallback(session, () -> markPlayingInGuild(session));
                        }
                    };
            Runnable onTerminated = () -> {
                        terminationObserved.set(true);
                        if (attached.get()) {
                            submitSessionCallback(session, () -> handleAudioSourceTerminationInGuild(session));
                        }
                    };
            if (audioDownloadManager == null) {
                source = audioSourceFactory.open(session.getCurrentTrack(), onFirstFrame, onTerminated);
            } else {
                if (preparedAudio == null) {
                    throw new DiscordAudioPreparationException("A faixa validada não está disponível.");
                }
                source = audioSourceFactory.open(
                        session.getCurrentTrack(),
                        preparedAudio.path(),
                        onFirstFrame,
                        onTerminated
                );
                if (!source.awaitInitialBuffer(
                        playbackProperties.getAudioInitialBuffer(),
                        playbackProperties.getAudioInitialBufferTimeout()
                )) {
                    source.close();
                    return AudioSourceOpening.failed(
                            new DiscordAudioPreparationException("O áudio não atingiu o buffer inicial no prazo."));
                }
            }
            LOGGER.info(
                    "Discord playback audio source created: guildId={}, sessionRef={}, source=ffmpeg",
                    session.getGuildId(),
                    DiscordDiagnosticSupport.pseudonymize(session.getSessionId())
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

    private PlaybackStartOutcome failPreparationInGuild(
            DiscordGuildPlaybackSession session,
            Throwable failure
    ) {
        if (sessionsByGuild.remove(session.getGuildId(), session)) {
            GuildQueueState queue = queuesByGuild.get(session.getGuildId());
            Optional<PlaybackItem> next = queue == null ? Optional.empty() : queue.queue().advance();
            boolean wasStarting = session.getState() == DiscordGuildPlaybackSession.PlaybackState.STARTING;
            session.beginStopping();
            session.close(next.isPresent() ? false : !wasStarting);
            session.notifyProgress(PlaybackProgress.FAILED);
            if (next.isPresent()) {
                startReplacementAfterFailureInGuild(session, next.get());
            } else {
                queuesByGuild.remove(session.getGuildId(), queue);
                try {
                    session.getVoiceConnection().closeAudioConnection();
                } catch (RuntimeException ignored) {
                    LOGGER.warn("Could not close voice connection after preparation failure: guildId={}",
                            session.getGuildId());
                }
            }
        }
        LOGGER.warn(
                "Discord playback preparation failed: guildId={}, sessionRef={}, reason={}",
                session.getGuildId(),
                DiscordDiagnosticSupport.pseudonymize(session.getSessionId()),
                unwrap(failure).getClass().getSimpleName()
        );
        return outcome(PlaybackStartResult.AUDIO_SOURCE_FAILED, null);
    }

    private Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private PlaybackStartOutcome completeStartInGuild(
            DiscordGuildPlaybackSession session,
            AudioSourceOpening opening
    ) {
        if (opening.failure() != null) {
            return failPreparationInGuild(session, opening.failure());
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
            LOGGER.info(
                    "Discord playback audio source attached: guildId={}, sessionRef={}, source=ffmpeg",
                    session.getGuildId(),
                    DiscordDiagnosticSupport.pseudonymize(session.getSessionId())
            );
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
            if (session.shouldRemainPausedAfterStart()
                    && session.getState() == DiscordGuildPlaybackSession.PlaybackState.PLAYING) {
                session.pause();
                session.notifyProgress(PlaybackProgress.PAUSED);
            }
            return outcome(PlaybackStartResult.STARTED, session.getSessionId());
        } catch (RuntimeException failure) {
            opening.attached().set(true);
            return failPreparationInGuild(session, failure);
        }
    }

    private PlaybackControlResult controlInGuild(
            Guild guild,
            Member requester,
            PlaybackControlRequest request
    ) {
        long guildId = guild.getIdLong();
        if (isDuplicateInteraction(guildId, request.interactionId())) {
            PlaybackControlResult duplicate = result(
                    PlaybackControlStatus.DUPLICATE,
                    "Esse controle já foi processado."
            );
            logControlOutcome(guildId, request, null, null, duplicate);
            return duplicate;
        }
        DiscordGuildPlaybackSession session = sessionsByGuild.get(guildId);
        if (session == null) {
            if (request.action() == PlaybackControlAction.BACK) {
                PlaybackControlResult idleBack = backFromIdleInGuild(guild, requester, request);
                logControlOutcome(guildId, request, null, null, idleBack);
                return idleBack;
            }
            releaseGuildMetadataIfInactive(guildId);
            PlaybackControlResult missing = result(
                    PlaybackControlStatus.NO_ACTIVE_SESSION,
                    "Não existe uma reprodução ativa nesta comunidade."
            );
            logControlOutcome(guildId, request, null, null, missing);
            return missing;
        }
        DiscordGuildPlaybackSession.PlaybackState previousState = session.getState();
        if (request.expectedSessionId() != null
                && !request.expectedSessionId().equals(session.getSessionId())) {
            PlaybackControlResult expired = result(
                    PlaybackControlStatus.EXPIRED_CONTROL,
                    "Esse controle pertence a uma reprodução encerrada."
            );
            logControlOutcome(guildId, request, session, previousState, expired);
            return expired;
        }
        if (request.expectedMessageId() != null
                && !session.matchesControlMessage(request.expectedMessageId())) {
            PlaybackControlResult expired = result(
                    PlaybackControlStatus.EXPIRED_CONTROL,
                    "Essa mensagem de controle não está mais ativa."
            );
            logControlOutcome(guildId, request, session, previousState, expired);
            return expired;
        }

        PlaybackControlResult authorization = authorizeController(session, requester);
        if (authorization != null) {
            logControlOutcome(guildId, request, session, previousState, authorization);
            return authorization;
        }

        PlaybackControlResult controlResult = switch (request.action()) {
            case PAUSE -> pauseInGuild(session);
            case RESUME -> resumeInGuild(session);
            case STOP -> stopAndKeepVoiceInGuild(session);
            case SKIP -> skipInGuild(session);
            case BACK -> backInGuild(session);
            case RESTART -> restartInGuild(session);
        };
        logControlOutcome(guildId, request, session, previousState, controlResult);
        return controlResult;
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
        GuildQueueState queue = queuesByGuild.remove(session.getGuildId());
        if (queue != null) {
            queue.queue().clearAll();
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
                "Discord playback cleanup completed: guildId={}, sessionRef={}, trackId={}, reason={}",
                session.getGuildId(),
                DiscordDiagnosticSupport.pseudonymize(session.getSessionId()),
                session.getCurrentTrack().id(),
                CleanupReason.USER_REQUESTED_STOP
        );
        return result(PlaybackControlStatus.APPLIED, "Reprodução encerrada.");
    }

    private PlaybackControlResult skipInGuild(DiscordGuildPlaybackSession session) {
        GuildQueueState queue = queuesByGuild.get(session.getGuildId());
        if (queue == null || queue.queue().pendingSize() == 0) {
            finishCurrentWithoutNextInGuild(session, IdleReason.NATURAL_TRACK_END);
            return result(PlaybackControlStatus.APPLIED, "Não havia outra faixa; a reprodução foi encerrada.");
        }
        Optional<PlaybackItem> next = queue.queue().advance();
        if (next.isEmpty()) {
            finishCurrentWithoutNextInGuild(session, IdleReason.NATURAL_TRACK_END);
            return result(PlaybackControlStatus.APPLIED, "Não havia outra faixa; a reprodução foi encerrada.");
        }
        replaceAndStartInGuild(session, next.get());
        return result(PlaybackControlStatus.APPLIED, "Pulando para a próxima faixa.");
    }

    private PlaybackControlResult backInGuild(DiscordGuildPlaybackSession session) {
        GuildQueueState queue = queuesByGuild.get(session.getGuildId());
        if (queue == null || queue.queue().historySize() == 0) {
            return result(PlaybackControlStatus.NO_HISTORY, "Ainda não existe uma faixa anterior nesta sessão.");
        }
        if (queue.queue().current() != null
                && queue.queue().pendingSize() >= playbackProperties.getQueueMaxSize()) {
            return result(PlaybackControlStatus.QUEUE_FULL, "A fila está cheia para voltar sem perder uma faixa pendente.");
        }
        Optional<PlaybackItem> previous = queue.queue().previous();
        if (previous.isEmpty()) {
            return result(PlaybackControlStatus.NO_HISTORY, "Ainda não existe uma faixa anterior nesta sessão.");
        }
        replaceAndStartInGuild(session, previous.get());
        return result(PlaybackControlStatus.APPLIED, "Voltando para a faixa anterior.");
    }

    private PlaybackControlResult backFromIdleInGuild(
            Guild guild,
            Member requester,
            PlaybackControlRequest request
    ) {
        long guildId = guild.getIdLong();
        if (request.expectedSessionId() != null || request.expectedMessageId() != null) {
            return result(PlaybackControlStatus.EXPIRED_CONTROL, "Esse controle pertence a uma reprodução encerrada.");
        }
        GuildQueueState queue = queuesByGuild.get(guildId);
        IdleVoiceConnection idle = idleConnectionsByGuild.get(guildId);
        if (queue == null || queue.queue().historySize() == 0 || idle == null) {
            return result(PlaybackControlStatus.NO_HISTORY, "Ainda não existe uma faixa anterior nesta sessão.");
        }
        VoiceChannel requesterChannel = getStandardVoiceChannel(requester);
        if (requesterChannel == null) {
            return result(
                    requesterHasAudioChannel(requester)
                            ? PlaybackControlStatus.UNSUPPORTED_CHANNEL
                            : PlaybackControlStatus.USER_NOT_IN_VOICE,
                    "Entre no canal de voz do bot para voltar à faixa anterior."
            );
        }
        if (requesterChannel.getIdLong() != idle.channelId()) {
            return result(PlaybackControlStatus.DIFFERENT_CHANNEL, "Você precisa estar no mesmo canal de voz do bot.");
        }
        if (!guild.getSelfMember().hasPermission(requesterChannel, Permission.VOICE_SPEAK)) {
            return result(PlaybackControlStatus.FAILED, "Não tenho permissão para falar nesse canal.");
        }
        Optional<PlaybackItem> previous = queue.queue().previous();
        if (previous.isEmpty()) {
            return result(PlaybackControlStatus.NO_HISTORY, "Ainda não existe uma faixa anterior nesta sessão.");
        }
        if (!idleConnectionsByGuild.remove(guildId, idle)) {
            return result(PlaybackControlStatus.EXPIRED_CONTROL, "A conexão ociosa mudou; tente novamente.");
        }
        idle.cancelTimeout();
        DiscordGuildPlaybackSession replacement = new DiscordGuildPlaybackSession(
                UUID.randomUUID().toString(),
                guildId,
                requesterChannel,
                idle.audioManager(),
                previous.get().track(),
                previous.get().remainPaused(),
                previous.get().progressObserver()
        );
        if (queue.controlMessageId() != null) {
            replacement.bindControlMessage(queue.controlMessageId());
        }
        sessionsByGuild.put(guildId, replacement);
        prepareAudioAsync(replacement)
                .thenCompose(prepared -> openAudioSourceAsync(replacement, prepared))
                .thenCompose(opening -> guildExecutor.submit(
                        guildId,
                        () -> completeStartInGuild(replacement, opening)
                ))
                .exceptionallyCompose(failure -> guildExecutor.submit(
                        guildId,
                        () -> failPreparationInGuild(replacement, failure)
                ));
        return result(PlaybackControlStatus.APPLIED, "Voltando para a faixa anterior.");
    }

    private PlaybackControlResult restartInGuild(DiscordGuildPlaybackSession session) {
        GuildQueueState queue = queuesByGuild.get(session.getGuildId());
        if (queue == null || queue.queue().current() == null) {
            return result(PlaybackControlStatus.NO_ACTIVE_SESSION, "Não existe uma faixa ativa para reiniciar.");
        }
        PlaybackItem current = queue.queue().current();
        boolean paused = session.getState() == DiscordGuildPlaybackSession.PlaybackState.PAUSED;
        queue.queue().setCurrent(new PlaybackItem(
                current.track(),
                current.progressObserver(),
                paused,
                current.requesterName(),
                current.voiceChannel()
        ));
        replaceAndStartInGuild(session, queue.queue().current());
        return result(PlaybackControlStatus.APPLIED, "Reiniciando a faixa atual.");
    }

    private void finishCurrentWithoutNextInGuild(
            DiscordGuildPlaybackSession session,
            IdleReason idleReason
    ) {
        if (!sessionsByGuild.remove(session.getGuildId(), session)) {
            return;
        }
        GuildQueueState queue = queuesByGuild.get(session.getGuildId());
        if (queue != null) {
            queue.queue().advance();
        }
        session.beginStopping();
        session.close(false);
        scheduleIdleConnectionInGuild(
                session.getGuildId(),
                session.getVoiceChannel(),
                session.getVoiceConnection(),
                session,
                idleReason
        );
        session.notifyProgress(PlaybackProgress.COMPLETED);
    }

    private void replaceAndStartInGuild(DiscordGuildPlaybackSession previous, PlaybackItem item) {
        long guildId = previous.getGuildId();
        if (!sessionsByGuild.remove(guildId, previous)) {
            return;
        }
        previous.beginStopping();
        previous.close(false);
        DiscordGuildPlaybackSession replacement = new DiscordGuildPlaybackSession(
                UUID.randomUUID().toString(),
                guildId,
                previous.getVoiceChannel(),
                previous.getVoiceConnection(),
                item.track(),
                item.remainPaused(),
                item.progressObserver()
        );
        GuildQueueState queue = queuesByGuild.get(guildId);
        if (queue != null && queue.controlMessageId() != null) {
            replacement.bindControlMessage(queue.controlMessageId());
        }
        sessionsByGuild.put(guildId, replacement);
        prepareAudioAsync(replacement)
                .thenCompose(prepared -> openAudioSourceAsync(replacement, prepared))
                .thenCompose(opening -> guildExecutor.submit(
                        guildId,
                        () -> completeStartInGuild(replacement, opening)
                ))
                .exceptionallyCompose(failure -> guildExecutor.submit(
                        guildId,
                        () -> failPreparationInGuild(replacement, failure)
                ));
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
                    "Discord playback first frame delivered: guildId={}, sessionRef={}, trackId={}",
                    session.getGuildId(),
                    DiscordDiagnosticSupport.pseudonymize(session.getSessionId()),
                    session.getCurrentTrack().id()
            );
            if (session.shouldRemainPausedAfterStart() && session.pause()) {
                session.notifyProgress(PlaybackProgress.PAUSED);
            } else {
                session.notifyProgress(PlaybackProgress.AUDIO_FRAMES_AVAILABLE);
            }
        }
    }

    private void expirePausedSessionInGuild(
            DiscordGuildPlaybackSession session,
            Object pauseGeneration
    ) {
        if (sessionsByGuild.get(session.getGuildId()) != session
                || !session.matchesPauseTimeout(pauseGeneration)
                || !sessionsByGuild.remove(session.getGuildId(), session)) {
            LOGGER.info(
                    "Discord playback callback ignored: kind=pause-timeout guildId={} sessionRef={} reason=stale",
                    session.getGuildId(),
                    DiscordDiagnosticSupport.pseudonymize(session.getSessionId())
            );
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
            LOGGER.info(
                    "Discord playback callback ignored: kind=cleanup guildId={} sessionRef={} reason=stale",
                    session.getGuildId(),
                    DiscordDiagnosticSupport.pseudonymize(session.getSessionId())
            );
            return;
        }
        cancelIdleConnectionInGuild(session.getGuildId(), null, "terminal_playback_cleanup");
        if (closeVoiceConnection) {
            GuildQueueState queue = queuesByGuild.remove(session.getGuildId());
            if (queue != null) {
                queue.queue().clearAll();
            }
        }
        session.beginStopping();
        session.close(closeVoiceConnection);
        LOGGER.info(
                "Discord playback cleanup completed: guildId={}, sessionRef={}, trackId={}, reason={}, detail={}",
                session.getGuildId(),
                DiscordDiagnosticSupport.pseudonymize(session.getSessionId()),
                session.getCurrentTrack().id(),
                reason,
                detail == null ? "none" : detail
        );
        notifyCleanupProgress(session, reason);
        releaseGuildMetadataIfInactive(session.getGuildId());
    }

    private void handleAudioSourceTerminationInGuild(DiscordGuildPlaybackSession session) {
        if (!sessionsByGuild.remove(session.getGuildId(), session)) {
            LOGGER.info(
                    "Discord playback callback ignored: kind=audio-termination guildId={} sessionRef={} reason=stale",
                    session.getGuildId(),
                    DiscordDiagnosticSupport.pseudonymize(session.getSessionId())
            );
            return;
        }
        DiscordGuildPlaybackSession.PlaybackState previousState = session.getState();
        boolean completed = previousState == DiscordGuildPlaybackSession.PlaybackState.PLAYING
                || previousState == DiscordGuildPlaybackSession.PlaybackState.PAUSED;
        GuildQueueState queue = queuesByGuild.get(session.getGuildId());
            Optional<PlaybackItem> next = queue == null ? Optional.empty() : queue.queue().advance();
        session.beginStopping();
        session.close(next.isPresent() ? false : !completed);
        if (next.isPresent()) {
            session.notifyProgress(completed ? PlaybackProgress.COMPLETED : PlaybackProgress.FAILED);
            startReplacementAfterFailureInGuild(session, next.get());
        } else if (completed) {
            scheduleIdleConnectionInGuild(
                    session.getGuildId(),
                    session.getVoiceChannel(),
                    session.getVoiceConnection(),
                    session,
                    IdleReason.NATURAL_TRACK_END
            );
        } else {
            queuesByGuild.remove(session.getGuildId(), queue);
        }
        LOGGER.info(
                "Discord playback cleanup completed: guildId={}, sessionRef={}, trackId={}, reason={}, detail=none",
                session.getGuildId(),
                DiscordDiagnosticSupport.pseudonymize(session.getSessionId()),
                session.getCurrentTrack().id(),
                CleanupReason.AUDIO_SOURCE_TERMINATED
        );
        if (next.isEmpty()) {
            session.notifyProgress(completed ? PlaybackProgress.COMPLETED : PlaybackProgress.FAILED);
        }
        releaseGuildMetadataIfInactive(session.getGuildId());
    }

    private void startReplacementAfterFailureInGuild(
            DiscordGuildPlaybackSession previous,
            PlaybackItem item
    ) {
        previous.close(false);
        DiscordGuildPlaybackSession replacement = new DiscordGuildPlaybackSession(
                UUID.randomUUID().toString(),
                previous.getGuildId(),
                previous.getVoiceChannel(),
                previous.getVoiceConnection(),
                item.track(),
                item.remainPaused(),
                item.progressObserver()
        );
        GuildQueueState queue = queuesByGuild.get(previous.getGuildId());
        if (queue != null && queue.controlMessageId() != null) {
            replacement.bindControlMessage(queue.controlMessageId());
        }
        sessionsByGuild.put(previous.getGuildId(), replacement);
        prepareAudioAsync(replacement)
                .thenCompose(prepared -> openAudioSourceAsync(replacement, prepared))
                .thenCompose(opening -> guildExecutor.submit(
                        previous.getGuildId(),
                        () -> completeStartInGuild(replacement, opening)
                ))
                .exceptionallyCompose(failure -> guildExecutor.submit(
                        previous.getGuildId(),
                        () -> failPreparationInGuild(replacement, failure)
                ));
    }

    private void handleVoiceStatusInGuild(DiscordGuildPlaybackSession session, ConnectionStatus status) {
        if (status == ConnectionStatus.CONNECTED && sessionsByGuild.get(session.getGuildId()) == session) {
            if (session.getState() == DiscordGuildPlaybackSession.PlaybackState.STARTING) {
                session.notifyProgress(PlaybackProgress.VOICE_CONNECTED);
            } else {
                LOGGER.info(
                        "Discord playback progress ignored: kind=voice-connected guildId={}, sessionRef={}, reason=state, state={}",
                        session.getGuildId(),
                        DiscordDiagnosticSupport.pseudonymize(session.getSessionId()),
                        session.getState()
                );
            }
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
            LOGGER.info(
                    "Discord playback callback ignored: kind=voice-status guildId={} sessionRef={} reason=stale status={}",
                    session.getGuildId(),
                    DiscordDiagnosticSupport.pseudonymize(session.getSessionId()),
                    status.name()
            );
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
            LOGGER.info(
                    "Discord playback callback ignored: kind=idle-timeout guildId={} reason=stale",
                    idleConnection.guildId()
            );
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
        GuildQueueState queue = queuesByGuild.remove(guildId);
        if (queue != null) {
            queue.queue().clearAll();
        }
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
        GuildQueueState queue = queuesByGuild.remove(guildId);
        if (queue != null) {
            queue.queue().clearAll();
        }
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

    private void logControlOutcome(
            long guildId,
            PlaybackControlRequest request,
            DiscordGuildPlaybackSession session,
            DiscordGuildPlaybackSession.PlaybackState previousState,
            PlaybackControlResult result
    ) {
        LOGGER.info(
                "Discord playback control: guildId={}, type={}, action={}, correlation={}, sessionRef={}, "
                        + "result={}, reason={}, stateBefore={}, stateAfter={}",
                guildId,
                request.expectedSessionId() == null ? "slash" : "button",
                request.action().name().toLowerCase(),
                DiscordDiagnosticSupport.pseudonymize(request.interactionId()),
                DiscordDiagnosticSupport.pseudonymize(session == null ? null : session.getSessionId()),
                result.status().name().toLowerCase(),
                result.status() == PlaybackControlStatus.APPLIED ? "applied" : "domain_result",
                previousState == null ? "none" : previousState.name(),
                session == null ? "none" : session.getState().name()
        );
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

    public record PlaybackStartOutcome(
            PlaybackStartResult result,
            String sessionId,
            int queuePosition,
            int pendingCount
    ) {

        public PlaybackStartOutcome(PlaybackStartResult result, String sessionId) {
            this(result, sessionId, 0, 0);
        }
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

    public record QueueCommandRequest(
            QueueAction action,
            int origin,
            int destination,
            String interactionId
    ) {

        public QueueCommandRequest {
            if (action == null) {
                throw new IllegalArgumentException("queue action is required");
            }
        }
    }

    public record QueueCommandResult(
            QueueCommandStatus status,
            String responseMessage,
            QueueSnapshot snapshot
    ) {
    }

    public enum QueueAction {
        VIEW,
        REMOVE,
        MOVE,
        CLEAR,
        SHUFFLE
    }

    public enum QueueCommandStatus {
        APPLIED,
        INVALID_POSITION,
        NO_ACTIVE_SESSION,
        REJECTED,
        DUPLICATE
    }

    public enum PlaybackControlAction {
        PAUSE,
        RESUME,
        STOP,
        SKIP,
        BACK,
        RESTART
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
        NO_HISTORY,
        QUEUE_FULL,
        DUPLICATE,
        FAILED
    }

    public enum PlaybackStartResult {
        STARTED,
        QUEUED,
        QUEUE_FULL,
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

    private record PlaybackItem(
            DiscordTrack track,
            BiConsumer<String, PlaybackProgress> progressObserver,
            boolean remainPaused,
            String requesterName,
            String voiceChannel
    ) {
    }

    private static final class GuildQueueState {

        private final DiscordPlaybackQueue<PlaybackItem> queue;
        private Long controlMessageId;

        private GuildQueueState(int maxPending, int maxHistory) {
            this.queue = new DiscordPlaybackQueue<>(maxPending, maxHistory);
        }

        private DiscordPlaybackQueue<PlaybackItem> queue() {
            return queue;
        }

        private Long controlMessageId() {
            return controlMessageId;
        }

        private void setControlMessageId(long controlMessageId) {
            this.controlMessageId = controlMessageId;
        }

        private QueueSnapshot snapshot() {
            PlaybackItem current = queue.current();
            return new QueueSnapshot(
                    current == null ? null : view(current),
                    queue.pendingSnapshot().stream().map(this::view).toList(),
                    queue.historySize()
            );
        }

        private QueueEntryView view(PlaybackItem item) {
            return new QueueEntryView(item.track(), item.requesterName(), item.voiceChannel());
        }
    }

    record QueueSnapshot(
            QueueEntryView current,
            java.util.List<QueueEntryView> pending,
            int historySize
    ) {

        static QueueSnapshot empty() {
            return new QueueSnapshot(null, java.util.List.of(), 0);
        }
    }

    record QueueEntryView(DiscordTrack track, String requester, String voiceChannel) {
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
