package br.com.deefy.discord;

import net.dv8tion.jda.api.audio.AudioSendHandler;

import java.time.Duration;

public interface DiscordAudioSource extends AudioSendHandler, AutoCloseable {

    void pause();

    void resume();

    boolean isPaused();

    /** Waits for startup audio without consuming frames. */
    default boolean awaitInitialBuffer(Duration minimum, Duration timeout) {
        return true;
    }

    @Override
    void close();
}
