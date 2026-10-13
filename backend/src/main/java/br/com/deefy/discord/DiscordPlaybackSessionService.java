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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class DiscordPlaybackSessionService {

    private final DiscordVoiceConnectionService voiceConnectionService;
    private final DiscordAudioSourceFactory audioSourceFactory;
    private final ConcurrentMap<Long, DiscordGuildPlaybackSession> sessionsByGuild = new ConcurrentHashMap<>();

    public DiscordPlaybackSessionService(
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordAudioSourceFactory audioSourceFactory
    ) {
        this.voiceConnectionService = voiceConnectionService;
        this.audioSourceFactory = audioSourceFactory;
    }

    public PlaybackStartResult start(Guild guild, Member requester, DiscordTrack track) {
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
                track
        );
        if (sessionsByGuild.putIfAbsent(guildId, session) != null) {
            return PlaybackStartResult.ALREADY_PLAYING;
        }

        DiscordVoiceConnectionService.VoiceCommandResult voiceResult = voiceConnectionService.join(
                guild,
                requester,
                status -> {
                    if (isTerminalVoiceStatus(status)) {
                        removeAndClose(session, false);
                    }
                }
        );
        PlaybackStartResult rejected = mapRejectedVoiceResult(voiceResult);
        if (rejected != null) {
            removeAndClose(session, false);
            return rejected;
        }

        try {
            DiscordAudioSource source = audioSourceFactory.open(
                    track,
                    () -> markPlaying(session),
                    () -> removeAndClose(session, true)
            );
            session.attach(source);
            audioManager.setSendingHandler(source);
            return PlaybackStartResult.STARTED;
        } catch (IOException | RuntimeException exception) {
            removeAndClose(session, true);
            return PlaybackStartResult.AUDIO_SOURCE_FAILED;
        }
    }

    public void stop(Guild guild) {
        if (guild == null) {
            return;
        }
        DiscordGuildPlaybackSession session = sessionsByGuild.remove(guild.getIdLong());
        if (session != null) {
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
                session.close(true);
            }
        });
    }

    private void markPlaying(DiscordGuildPlaybackSession session) {
        if (sessionsByGuild.get(session.getGuildId()) == session) {
            session.markPlaying();
        }
    }

    private void removeAndClose(DiscordGuildPlaybackSession session, boolean closeVoiceConnection) {
        if (sessionsByGuild.remove(session.getGuildId(), session)) {
            session.close(closeVoiceConnection);
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
}
