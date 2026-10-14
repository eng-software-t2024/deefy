package br.com.deefy.discord;

import org.springframework.stereotype.Component;

import java.io.IOException;

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
        return new DiscordFfmpegAudioSource(
                processFactory.start(track),
                track.id(),
                onFirstFrame,
                onTerminated
        );
    }
}
