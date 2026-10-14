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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class DiscordPlaybackSessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordPlaybackSessionService.class);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);

    private final DiscordVoiceConnectionService voiceConnectionService;
    private final DiscordAudioSourceFactory audioSourceFactory;
    private final ScheduledExecutorService scheduler;
    private final Duration startupTimeout;
    private final ConcurrentMap<Long, DiscordGuildPlaybackSession> sessionsByGuild = new ConcurrentHashMap<>();

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
                STARTUP_TIMEOUT
        );
    }

    DiscordPlaybackSessionService(
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordAudioSourceFactory audioSourceFactory,
            ScheduledExecutorService scheduler,
            Duration startupTimeout
    ) {
        this.voiceConnectionService = voiceConnectionService;
        this.audioSourceFactory = audioSourceFactory;
        this.scheduler = scheduler;
        this.startupTimeout = startupTimeout;
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
        if (sessionsByGuild.putIfAbsent(guildId, session) != null) {
            return PlaybackStartResult.ALREADY_PLAYING;
        }

        LOGGER.info("Discord playback session created: guildId={}, trackId={}", guildId, track.id());

        DiscordVoiceConnectionService.VoiceCommandResult voiceResult = voiceConnectionService.join(
                guild,
                requester,
                status -> {
                    if (status == ConnectionStatus.CONNECTED
                            && sessionsByGuild.get(session.getGuildId()) == session) {
                        session.notifyProgress(PlaybackProgress.VOICE_CONNECTED);
                    }
                    if (isTerminalVoiceStatus(status)) {
                        removeAndClose(
                                session,
                                true,
                                CleanupReason.VOICE_TERMINAL_STATUS,
                                status.name()
                        );
                    }
                }
        );
        PlaybackStartResult rejected = mapRejectedVoiceResult(voiceResult);
        if (rejected != null) {
            removeAndClose(session, false, CleanupReason.VOICE_START_REJECTED, voiceResult.name());
            return rejected;
        }

        try {
            DiscordAudioSource source = audioSourceFactory.open(
                    track,
                    () -> markPlaying(session),
                    () -> removeAndClose(session, true, CleanupReason.AUDIO_SOURCE_TERMINATED, null)
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
        DiscordGuildPlaybackSession session = sessionsByGuild.remove(guild.getIdLong());
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

    @PreDestroy
    public void closeAll() {
        sessionsByGuild.forEach((guildId, session) -> {
            if (sessionsByGuild.remove(guildId, session)) {
                LOGGER.info(
                        "Discord playback cleanup: guildId={}, trackId={}, reason={}",
                        session.getGuildId(),
                        session.getCurrentTrack().id(),
                        CleanupReason.APPLICATION_SHUTDOWN
                );
                session.close(true);
            }
        });
        scheduler.shutdownNow();
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
        if (sessionsByGuild.remove(session.getGuildId(), session)) {
            DiscordGuildPlaybackSession.PlaybackState previousState = session.getState();
            LOGGER.info(
                    "Discord playback cleanup: guildId={}, trackId={}, reason={}, detail={}",
                    session.getGuildId(),
                    session.getCurrentTrack().id(),
                    reason,
                    detail == null ? "none" : detail
            );
            session.close(closeVoiceConnection);
            notifyCleanupProgress(session, previousState, reason);
        }
    }

    private void notifyCleanupProgress(
            DiscordGuildPlaybackSession session,
            DiscordGuildPlaybackSession.PlaybackState previousState,
            CleanupReason reason
    ) {
        if (reason == CleanupReason.USER_REQUESTED_STOP
                || reason == CleanupReason.APPLICATION_SHUTDOWN
                || reason == CleanupReason.VOICE_START_REJECTED) {
            return;
        }
        if (reason == CleanupReason.AUDIO_SOURCE_TERMINATED
                && previousState == DiscordGuildPlaybackSession.PlaybackState.PLAYING) {
            session.notifyProgress(PlaybackProgress.COMPLETED);
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
}
