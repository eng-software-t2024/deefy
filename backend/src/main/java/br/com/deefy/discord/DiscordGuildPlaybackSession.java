package br.com.deefy.discord;

import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;

import java.util.Objects;

public final class DiscordGuildPlaybackSession implements AutoCloseable {

    private final long guildId;
    private final VoiceChannel voiceChannel;
    private final AudioManager voiceConnection;
    private final DiscordTrack currentTrack;

    private DiscordAudioSource audioSource;
    private PlaybackState state = PlaybackState.STARTING;

    public DiscordGuildPlaybackSession(
            long guildId,
            VoiceChannel voiceChannel,
            AudioManager voiceConnection,
            DiscordTrack currentTrack
    ) {
        this.guildId = guildId;
        this.voiceChannel = Objects.requireNonNull(voiceChannel);
        this.voiceConnection = Objects.requireNonNull(voiceConnection);
        this.currentTrack = Objects.requireNonNull(currentTrack);
    }

    public long getGuildId() {
        return guildId;
    }

    public VoiceChannel getVoiceChannel() {
        return voiceChannel;
    }

    public AudioManager getVoiceConnection() {
        return voiceConnection;
    }

    public DiscordTrack getCurrentTrack() {
        return currentTrack;
    }

    public synchronized PlaybackState getState() {
        return state;
    }

    public synchronized void attach(DiscordAudioSource source) {
        if (state == PlaybackState.CLOSED) {
            source.close();
            throw new IllegalStateException("Playback session is already closed");
        }
        audioSource = Objects.requireNonNull(source);
    }

    public synchronized void markPlaying() {
        if (state == PlaybackState.STARTING) {
            state = PlaybackState.PLAYING;
        }
    }

    @Override
    public void close() {
        close(true);
    }

    public void close(boolean closeVoiceConnection) {
        DiscordAudioSource source;
        synchronized (this) {
            if (state == PlaybackState.CLOSED) {
                return;
            }
            state = PlaybackState.CLOSED;
            source = audioSource;
            audioSource = null;
        }

        try {
            voiceConnection.setSendingHandler(null);
        } catch (RuntimeException ignored) {
            // Cleanup continues without exposing JDA internals or media URLs.
        }
        if (source != null) {
            source.close();
        }

        if (closeVoiceConnection) {
            try {
                voiceConnection.closeAudioConnection();
            } catch (RuntimeException ignored) {
                // Cleanup is best effort and remains scoped to this guild.
            }
        }
    }

    public enum PlaybackState {
        STARTING,
        PLAYING,
        CLOSED
    }
}
