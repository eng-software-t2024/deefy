package br.com.deefy.discord;

import java.io.IOException;
import java.nio.file.Path;

public interface DiscordAudioSourceFactory {

    DiscordAudioSource open(
            DiscordTrack track,
            Runnable onFirstFrame,
            Runnable onTerminated
    ) throws IOException;

    default DiscordAudioSource open(
            DiscordTrack track,
            Path validatedAudioFile,
            Runnable onFirstFrame,
            Runnable onTerminated
    ) throws IOException {
        throw new IOException("Validated local audio is required before opening FFmpeg.");
    }
}
