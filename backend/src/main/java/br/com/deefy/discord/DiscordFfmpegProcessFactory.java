package br.com.deefy.discord;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class DiscordFfmpegProcessFactory {

    private final DiscordPlaybackProperties properties;

    public DiscordFfmpegProcessFactory(DiscordPlaybackProperties properties) {
        this.properties = properties;
    }

    public Process start(DiscordTrack track) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(List.of(
                properties.getFfmpegExecutable(),
                "-nostdin",
                "-hide_banner",
                "-loglevel", "error",
                "-i", track.audioUrl(),
                "-vn",
                "-ac", "2",
                "-ar", "48000",
                "-f", "s16be",
                "pipe:1"
        ));
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return processBuilder.start();
    }
}
