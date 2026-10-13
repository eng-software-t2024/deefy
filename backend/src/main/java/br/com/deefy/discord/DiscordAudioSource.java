package br.com.deefy.discord;

import net.dv8tion.jda.api.audio.AudioSendHandler;

public interface DiscordAudioSource extends AudioSendHandler, AutoCloseable {

    @Override
    void close();
}
