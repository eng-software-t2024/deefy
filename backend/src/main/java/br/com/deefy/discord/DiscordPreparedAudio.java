package br.com.deefy.discord;

import java.nio.file.Path;

public record DiscordPreparedAudio(
        long trackId,
        Path path,
        String sha256,
        String etag,
        long lastModified
) {
}
