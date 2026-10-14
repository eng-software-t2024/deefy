package br.com.deefy.discord;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

@Component
public class DiscordFfmpegProcessFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordFfmpegProcessFactory.class);

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
        Process process = processBuilder.start();
        LOGGER.info("FFmpeg started: trackId={}", track.id());
        return process;
    }
}
