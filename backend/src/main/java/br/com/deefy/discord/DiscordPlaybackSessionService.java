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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class DiscordPlaybackSessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordPlaybackSessionService.class);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration IDLE_DISCONNECT_TIMEOUT = Duration.ofMinutes(10);

    private final DiscordVoiceConnectionService voiceConnectionService;
    private final DiscordAudioSourceFactory audioSourceFactory;
    private final ScheduledExecutorService scheduler;
    private final Duration startupTimeout;
    private final Duration idleDisconnectTimeout;
    private final ConcurrentMap<Long, DiscordGuildPlaybackSession> sessionsByGuild = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, IdleVoiceConnection> idleConnectionsByGuild = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Object> guildLocks = new ConcurrentHashMap<>();

    @Autowired
    public DiscordPlaybackSessionService(
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordAudioSourceFactory audioSourceFactory
    ) {
        this(
                voiceConnectionService,
                audioSourceFactory,
                Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform()
                        .daemon(true)
                        .name("deefy-discord-playback-timeout")
                        .unstarted(runnable)),
                STARTUP_TIMEOUT,
                IDLE_DISCONNECT_TIMEOUT
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
                scheduler,
                startupTimeout,
                IDLE_DISCONNECT_TIMEOUT
        );
    }

    DiscordPlaybackSessionService(
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordAudioSourceFactory audioSourceFactory,
            ScheduledExecutorService scheduler,
            Duration startupTimeout,
            Duration idleDisconnectTimeout
    ) {
        this.voiceConnectionService = voiceConnectionService;
        this.audioSourceFactory = audioSourceFactory;
        this.scheduler = scheduler;
        this.startupTimeout = startupTimeout;
        this.idleDisconnectTimeout = idleDisconnectTimeout;
    }

    public DiscordVoiceConnectionService.VoiceCommandResult joinIdle(Guild guild, Member requester) {
        VoiceChannel requestedChannel = getStandardVoiceChannel(requester);
        Object ownerToken = new Object();
        AtomicBoolean terminalStatusObserved = new AtomicBoolean(false);
        DiscordVoiceConnectionService.VoiceCommandResult result = voiceConnectionService.join(
                guild,
                requester,
                status -> {
                    if (isTerminalVoiceStatus(status)) {
                        terminalStatusObserved.set(true);
                        cancelIdleConnection(guild.getIdLong(), ownerToken, status.name());
                    }
                }
        );

        if ((result == DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED
                || result == DiscordVoiceConnectionService.VoiceCommandResult.ALREADY_IN_CHANNEL)
                && requestedChannel != null
                && !terminalStatusObserved.get()) {
            scheduleIdleConnection(
                    guild.getIdLong(),
                    requestedChannel,
                    guild.getAudioManager(),
                    ownerToken,
                    IdleReason.JOIN_WITHOUT_PLAYBACK
            );
        }
        return result;
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
        VoiceChannel requestedChannel = getStandardVoiceChannel(requester);
        if (requestedChannel == null) {
            return requesterHasAudioChannel(requester)
                    ? PlaybackStartResult.UNSUPPORTED_CHANNEL
                    : PlaybackStartResult.USER_NOT_IN_VOICE;
        }
        if (!guild.getSelfMember().hasPermission(requestedChannel, Permission.VOICE_SPEAK)) {
            return PlaybackStartResult.MISSING_PERMISSIONS;
        }

        long guildId = guild.getIdLong();
        AudioManager audioManager = guild.getAudioManager();
        DiscordGuildPlaybackSession session = new DiscordGuildPlaybackSession(
                guildId,
                requestedChannel,
                audioManager,
                track,
                progressObserver
        );
        Object guildLock = lockFor(guildId);
        DiscordVoiceConnectionService.VoiceCommandResult voiceResult;
        PlaybackStartResult rejected;
        synchronized (guildLock) {
            if (sessionsByGuild.putIfAbsent(guildId, session) != null) {
                return PlaybackStartResult.ALREADY_PLAYING;
            }
            voiceResult = voiceConnectionService.join(
                    guild,
                    requester,
                    status -> {
                        if (status == ConnectionStatus.CONNECTED
                                && sessionsByGuild.get(session.getGuildId()) == session) {
                            session.notifyProgress(PlaybackProgress.VOICE_CONNECTED);
                        }
                        if (isTerminalVoiceStatus(status)) {
                            handleTerminalVoiceStatus(session, status);
                        }
                    }
            );
            rejected = mapRejectedVoiceResult(voiceResult);
            if (rejected == null && sessionsByGuild.get(guildId) == session) {
                cancelIdleConnectionLocked(guildId, null, "valid_playback_request");
            } else if (rejected != null) {
                sessionsByGuild.remove(guildId, session);
                if (rejected == PlaybackStartResult.VOICE_CONNECTION_FAILED) {
                    cancelIdleConnectionLocked(guildId, null, "fatal_voice_start_failure");
                }
            }
        }

        LOGGER.info("Discord playback session created: guildId={}, trackId={}", guildId, track.id());

        if (rejected != null) {
            LOGGER.info(
                    "Discord playback cleanup: guildId={}, trackId={}, reason={}, detail={}",
                    guildId,
                    track.id(),
                    CleanupReason.VOICE_START_REJECTED,
                    voiceResult.name()
            );
            session.close(rejected == PlaybackStartResult.VOICE_CONNECTION_FAILED);
            return rejected;
        }
        if (sessionsByGuild.get(guildId) != session) {
            return PlaybackStartResult.VOICE_CONNECTION_FAILED;
        }

        try {
            DiscordAudioSource source = audioSourceFactory.open(
                    track,
                    () -> markPlaying(session),
                    () -> handleAudioSourceTermination(session)
            );
            session.attach(source);
            audioManager.setSendingHandler(source);
            session.setStartupTimeout(scheduler.schedule(
                    () -> removeAndClose(
                            session,
                            true,
                            CleanupReason.PLAYBACK_START_TIMEOUT,
                            null
                    ),
                    startupTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
            ));
            return PlaybackStartResult.STARTED;
        } catch (IOException | RuntimeException exception) {
            removeAndClose(session, true, CleanupReason.AUDIO_SOURCE_START_FAILED, null);
            return PlaybackStartResult.AUDIO_SOURCE_FAILED;
        }
    }

    public void stop(Guild guild) {
        if (guild == null) {
            return;
        }
        long guildId = guild.getIdLong();
        DiscordGuildPlaybackSession session;
        synchronized (lockFor(guildId)) {
            session = sessionsByGuild.remove(guildId);
            cancelIdleConnectionLocked(guildId, null, "user_requested_stop");
        }
        if (session != null) {
            LOGGER.info(
                    "Discord playback cleanup: guildId={}, trackId={}, reason={}",
                    session.getGuildId(),
                    session.getCurrentTrack().id(),
                    CleanupReason.USER_REQUESTED_STOP
            );
            session.close(false);
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

    @PreDestroy
    public void closeAll() {
        sessionsByGuild.keySet().forEach(guildId -> {
            synchronized (lockFor(guildId)) {
                DiscordGuildPlaybackSession session = sessionsByGuild.remove(guildId);
                if (session != null) {
                    LOGGER.info(
                            "Discord playback cleanup: guildId={}, trackId={}, reason={}",
                            session.getGuildId(),
                            session.getCurrentTrack().id(),
                            CleanupReason.APPLICATION_SHUTDOWN
                    );
                    session.close(true);
                }
            }
        });
        idleConnectionsByGuild.keySet().forEach(guildId -> {
            synchronized (lockFor(guildId)) {
                IdleVoiceConnection idleConnection = idleConnectionsByGuild.remove(guildId);
                if (idleConnection != null) {
                    idleConnection.cancelTimeout();
                    closeIdleVoiceConnection(idleConnection, IdleReason.APPLICATION_SHUTDOWN);
                }
            }
        });
        scheduler.shutdownNow();
        guildLocks.clear();
    }

    private void markPlaying(DiscordGuildPlaybackSession session) {
        if (sessionsByGuild.get(session.getGuildId()) == session && session.markPlaying()) {
            LOGGER.info(
                    "Discord playback first frame delivered: guildId={}, trackId={}",
                    session.getGuildId(),
                    session.getCurrentTrack().id()
            );
            session.notifyProgress(PlaybackProgress.AUDIO_FRAMES_AVAILABLE);
        }
    }

    private void removeAndClose(
            DiscordGuildPlaybackSession session,
            boolean closeVoiceConnection,
            CleanupReason reason,
            String detail
    ) {
        synchronized (lockFor(session.getGuildId())) {
            if (!sessionsByGuild.remove(session.getGuildId(), session)) {
                return;
            }
            cancelIdleConnectionLocked(session.getGuildId(), null, "terminal_playback_cleanup");
        }
        LOGGER.info(
                "Discord playback cleanup: guildId={}, trackId={}, reason={}, detail={}",
                session.getGuildId(),
                session.getCurrentTrack().id(),
                reason,
                detail == null ? "none" : detail
        );
        session.close(closeVoiceConnection);
        notifyCleanupProgress(session, reason);
    }

    private void handleAudioSourceTermination(DiscordGuildPlaybackSession session) {
        DiscordGuildPlaybackSession.PlaybackState previousState;
        synchronized (lockFor(session.getGuildId())) {
            if (!sessionsByGuild.remove(session.getGuildId(), session)) {
                return;
            }
            previousState = session.getState();
            if (previousState == DiscordGuildPlaybackSession.PlaybackState.PLAYING) {
                session.close(false);
                scheduleIdleConnectionLocked(
                        session.getGuildId(),
                        session.getVoiceChannel(),
                        session.getVoiceConnection(),
                        session,
                        IdleReason.NATURAL_TRACK_END
                );
            } else {
                session.close(true);
            }
        }
        LOGGER.info(
                "Discord playback cleanup: guildId={}, trackId={}, reason={}, detail=none",
                session.getGuildId(),
                session.getCurrentTrack().id(),
                CleanupReason.AUDIO_SOURCE_TERMINATED
        );
        session.notifyProgress(previousState == DiscordGuildPlaybackSession.PlaybackState.PLAYING
                ? PlaybackProgress.COMPLETED
                : PlaybackProgress.FAILED);
    }

    private void handleTerminalVoiceStatus(
            DiscordGuildPlaybackSession session,
            ConnectionStatus status
    ) {
        if (sessionsByGuild.get(session.getGuildId()) == session) {
            removeAndClose(
                    session,
                    true,
                    CleanupReason.VOICE_TERMINAL_STATUS,
                    status.name()
            );
            return;
        }
        cancelIdleConnection(session.getGuildId(), session, status.name());
    }

    private void scheduleIdleConnection(
            long guildId,
            VoiceChannel voiceChannel,
            AudioManager audioManager,
            Object ownerToken,
            IdleReason reason
    ) {
        synchronized (lockFor(guildId)) {
            scheduleIdleConnectionLocked(guildId, voiceChannel, audioManager, ownerToken, reason);
        }
    }

    private void scheduleIdleConnectionLocked(
            long guildId,
            VoiceChannel voiceChannel,
            AudioManager audioManager,
            Object ownerToken,
            IdleReason reason
    ) {
        if (sessionsByGuild.containsKey(guildId)) {
            return;
        }
        cancelIdleConnectionLocked(guildId, null, "idle_timer_replaced");
        IdleVoiceConnection idleConnection = new IdleVoiceConnection(
                guildId,
                voiceChannel.getIdLong(),
                audioManager,
                ownerToken
        );
        idleConnectionsByGuild.put(guildId, idleConnection);
        idleConnection.setTimeout(scheduler.schedule(
                () -> disconnectIfStillIdle(idleConnection),
                idleDisconnectTimeout.toMillis(),
                TimeUnit.MILLISECONDS
        ));
        LOGGER.info(
                "Discord voice idle timer scheduled: guildId={}, channelId={}, reason={}, timeoutSeconds={}",
                guildId,
                voiceChannel.getIdLong(),
                reason,
                idleDisconnectTimeout.toSeconds()
        );
    }

    private void disconnectIfStillIdle(IdleVoiceConnection idleConnection) {
        synchronized (lockFor(idleConnection.guildId())) {
            if (idleConnectionsByGuild.get(idleConnection.guildId()) != idleConnection
                    || sessionsByGuild.containsKey(idleConnection.guildId())) {
                return;
            }
            idleConnectionsByGuild.remove(idleConnection.guildId(), idleConnection);
            closeIdleVoiceConnection(idleConnection, IdleReason.IDLE_TIMEOUT);
        }
    }

    private void cancelIdleConnection(long guildId, Object ownerToken, String detail) {
        synchronized (lockFor(guildId)) {
            cancelIdleConnectionLocked(guildId, ownerToken, detail);
        }
    }

    private void cancelIdleConnectionLocked(long guildId, Object ownerToken, String detail) {
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

    private Object lockFor(long guildId) {
        return guildLocks.computeIfAbsent(guildId, ignored -> new Object());
    }

    private void notifyCleanupProgress(
            DiscordGuildPlaybackSession session,
            CleanupReason reason
    ) {
        if (reason == CleanupReason.USER_REQUESTED_STOP
                || reason == CleanupReason.APPLICATION_SHUTDOWN
                || reason == CleanupReason.VOICE_START_REJECTED) {
            return;
        }
        session.notifyProgress(reason == CleanupReason.PLAYBACK_START_TIMEOUT
                ? PlaybackProgress.START_TIMEOUT
                : PlaybackProgress.FAILED);
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

    public enum PlaybackStartResult {
        STARTED,
        ALREADY_PLAYING,
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
        USER_REQUESTED_STOP,
        APPLICATION_SHUTDOWN
    }

    public enum PlaybackProgress {
        VOICE_CONNECTED,
        AUDIO_FRAMES_AVAILABLE,
        START_TIMEOUT,
        FAILED,
        COMPLETED
    }

    private enum IdleReason {
        JOIN_WITHOUT_PLAYBACK,
        NATURAL_TRACK_END,
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
