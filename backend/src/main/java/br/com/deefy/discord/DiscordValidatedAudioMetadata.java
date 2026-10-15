package br.com.deefy.discord;

public record DiscordValidatedAudioMetadata(
        String codec,
        int sampleRate,
        int channels,
        long durationMillis,
        Long bitrate,
        long size,
        String sha256
) {
}
