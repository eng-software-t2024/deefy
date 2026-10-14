package br.com.deefy.discord;

import net.dv8tion.jda.api.audio.AudioSendHandler;

public interface DiscordAudioSource extends AudioSendHandler, AutoCloseable {

    void pause();

    void resume();

    boolean isPaused();

    @Override
    void close();
}
