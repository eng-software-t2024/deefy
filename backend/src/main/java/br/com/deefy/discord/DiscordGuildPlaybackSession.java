package br.com.deefy.discord;

import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

public final class DiscordGuildPlaybackSession implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordGuildPlaybackSession.class);

    private final String sessionId;
    private final long guildId;
    private final VoiceChannel voiceChannel;
    private final AudioManager voiceConnection;
    private final DiscordTrack currentTrack;
    private final Consumer<DiscordPlaybackSessionService.PlaybackProgress> progressObserver;

    private DiscordAudioSource audioSource;
    private ScheduledFuture<?> startupTimeout;
    private ScheduledFuture<?> pauseTimeout;
    private Object pauseTimeoutGeneration;
    private Long controlMessageId;
    private PlaybackState state = PlaybackState.STARTING;
    private boolean resourcesClosed;

    public DiscordGuildPlaybackSession(
            String sessionId,
            long guildId,
            VoiceChannel voiceChannel,
            AudioManager voiceConnection,
            DiscordTrack currentTrack,
            Consumer<DiscordPlaybackSessionService.PlaybackProgress> progressObserver
    ) {
        this.sessionId = Objects.requireNonNull(sessionId);
        this.guildId = guildId;
        this.voiceChannel = Objects.requireNonNull(voiceChannel);
        this.voiceConnection = Objects.requireNonNull(voiceConnection);
        this.currentTrack = Objects.requireNonNull(currentTrack);
        this.progressObserver = Objects.requireNonNull(progressObserver);
    }

    public String getSessionId() {
        return sessionId;
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
        if (state == PlaybackState.STOPPING || resourcesClosed) {
            source.close();
            throw new IllegalStateException("Playback session is stopping");
        }
        audioSource = Objects.requireNonNull(source);
    }

    public synchronized boolean markPlaying() {
        if (state == PlaybackState.STARTING) {
            state = PlaybackState.PLAYING;
            cancelStartupTimeoutLocked();
            return true;
        }
        return false;
    }

    public boolean pause() {
        DiscordAudioSource source;
        synchronized (this) {
            if (state != PlaybackState.PLAYING || audioSource == null) {
                return false;
            }
            state = PlaybackState.PAUSED;
            source = audioSource;
        }
        source.pause();
        return true;
    }

    public boolean resumePlayback() {
        DiscordAudioSource source;
        synchronized (this) {
            if (state != PlaybackState.PAUSED || audioSource == null) {
                return false;
            }
            state = PlaybackState.PLAYING;
            cancelPauseTimeoutLocked();
            source = audioSource;
        }
        source.resume();
        return true;
    }

    public synchronized boolean beginStopping() {
        if (state == PlaybackState.STOPPING) {
            return false;
        }
        state = PlaybackState.STOPPING;
        cancelStartupTimeoutLocked();
        cancelPauseTimeoutLocked();
        return true;
    }

    public synchronized void setStartupTimeout(ScheduledFuture<?> timeout) {
        if (state == PlaybackState.STARTING) {
            startupTimeout = timeout;
        } else {
            timeout.cancel(false);
        }
    }

    public synchronized Object beginPauseTimeoutGeneration() {
        cancelPauseTimeoutLocked();
        if (state == PlaybackState.PAUSED) {
            pauseTimeoutGeneration = new Object();
            return pauseTimeoutGeneration;
        }
        return null;
    }

    public synchronized void setPauseTimeout(Object generation, ScheduledFuture<?> timeout) {
        if (state == PlaybackState.PAUSED && pauseTimeoutGeneration == generation) {
            pauseTimeout = timeout;
        } else {
            timeout.cancel(false);
        }
    }

    public synchronized boolean matchesPauseTimeout(Object generation) {
        return state == PlaybackState.PAUSED && pauseTimeoutGeneration == generation;
    }

    public synchronized void cancelPauseTimeout() {
        cancelPauseTimeoutLocked();
    }

    public synchronized boolean bindControlMessage(long messageId) {
        if (state == PlaybackState.STOPPING) {
            return false;
        }
        if (controlMessageId == null) {
            controlMessageId = messageId;
            return true;
        }
        return controlMessageId == messageId;
    }

    public synchronized boolean matchesControlMessage(long messageId) {
        return controlMessageId != null && controlMessageId == messageId;
    }

    public synchronized OptionalLong getControlMessageId() {
        return controlMessageId == null ? OptionalLong.empty() : OptionalLong.of(controlMessageId);
    }

    public void notifyProgress(DiscordPlaybackSessionService.PlaybackProgress progress) {
        try {
            progressObserver.accept(progress);
        } catch (RuntimeException ignored) {
            LOGGER.warn("Could not notify Discord playback progress: guildId={}, trackId={}",
                    guildId, currentTrack.id());
        }
    }

    @Override
    public void close() {
        close(true);
    }

    public void close(boolean closeVoiceConnection) {
        DiscordAudioSource source;
        synchronized (this) {
            if (resourcesClosed) {
                return;
            }
            resourcesClosed = true;
            state = PlaybackState.STOPPING;
            cancelStartupTimeoutLocked();
            cancelPauseTimeoutLocked();
            source = audioSource;
            audioSource = null;
        }

        try {
            voiceConnection.setSendingHandler(null);
        } catch (RuntimeException ignored) {
            LOGGER.warn("Could not clear Discord audio handler during cleanup: guildId={}, trackId={}",
                    guildId, currentTrack.id());
        }
        if (source != null) {
            source.close();
        }

        if (closeVoiceConnection) {
            try {
                voiceConnection.closeAudioConnection();
            } catch (RuntimeException ignored) {
                LOGGER.warn("Could not close Discord voice connection during cleanup: guildId={}, trackId={}",
                        guildId, currentTrack.id());
            }
        }
    }

    private void cancelStartupTimeoutLocked() {
        if (startupTimeout != null) {
            startupTimeout.cancel(false);
            startupTimeout = null;
        }
    }

    private void cancelPauseTimeoutLocked() {
        if (pauseTimeout != null) {
            pauseTimeout.cancel(false);
            pauseTimeout = null;
        }
        pauseTimeoutGeneration = null;
    }

    public enum PlaybackState {
        IDLE,
        STARTING,
        PLAYING,
        PAUSED,
        STOPPING
    }
}
