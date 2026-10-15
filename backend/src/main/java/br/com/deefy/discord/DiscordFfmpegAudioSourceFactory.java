package br.com.deefy.discord;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

@Component
public class DiscordFfmpegAudioSourceFactory implements DiscordAudioSourceFactory {

    private final DiscordFfmpegProcessFactory processFactory;

    public DiscordFfmpegAudioSourceFactory(DiscordFfmpegProcessFactory processFactory) {
        this.processFactory = processFactory;
    }

    @Override
    public DiscordAudioSource open(
            DiscordTrack track,
            Runnable onFirstFrame,
            Runnable onTerminated
    ) throws IOException {
        throw new IOException("Validated local audio is required before opening FFmpeg.");
    }

    @Override
    public DiscordAudioSource open(
            DiscordTrack track,
            Path validatedAudioFile,
            Runnable onFirstFrame,
            Runnable onTerminated
    ) throws IOException {
        return new DiscordFfmpegAudioSource(
                processFactory.start(validatedAudioFile, track.id()),
                track.id(),
                onFirstFrame,
                onTerminated
        );
    }
}
