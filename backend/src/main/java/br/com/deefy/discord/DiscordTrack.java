package br.com.deefy.discord;

public record DiscordTrack(
        Long id,
        String title,
        String artist,
        String audioUrl
) {
}
