package br.com.deefy.discord;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Path;

@Component
public class DiscordFfmpegAudioSourceFactory implements DiscordAudioSourceFactory {

    private final DiscordFfmpegProcessFactory processFactory;
    private final DiscordPlaybackProperties properties;

    @Autowired
    public DiscordFfmpegAudioSourceFactory(
            DiscordFfmpegProcessFactory processFactory,
            DiscordPlaybackProperties properties
    ) {
        this.processFactory = processFactory;
        this.properties = properties;
    }

    DiscordFfmpegAudioSourceFactory(DiscordFfmpegProcessFactory processFactory) {
        this(processFactory, new DiscordPlaybackProperties());
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
                properties.initialBufferFrames(),
                onFirstFrame,
                onTerminated
        );
    }
}
