package br.com.deefy.discord;

import java.io.IOException;

public interface DiscordAudioSourceFactory {

    DiscordAudioSource open(
            DiscordTrack track,
            Runnable onFirstFrame,
            Runnable onTerminated
    ) throws IOException;
}
