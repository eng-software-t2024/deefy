package br.com.deefy.discord;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordPlaybackPropertiesTest {

    private final DiscordPlaybackProperties properties = new DiscordPlaybackProperties();

    @Test
    void shouldAcceptOnlyUnsignedUrlsFromConfiguredSelfHostedOrigin() {
        properties.setApiUrl("https://deefy.olua.me");

        assertThat(properties.isSelfHostedAudioUrl(
                "https://deefy.olua.me/media/musicas/faixa.mp3"
        )).isTrue();
        assertThat(properties.isSelfHostedAudioUrl(
                "https://outro-host.example/media/musicas/faixa.mp3"
        )).isFalse();
        assertThat(properties.isSelfHostedAudioUrl(
                "https://deefy.olua.me/media/musicas/faixa.mp3?signature=fixture"
        )).isFalse();
    }

    @Test
    void shouldExplicitlyRejectLegacySupabaseHost() {
        properties.setApiUrl("https://prqdlbsazlodierobswv.supabase.co");

        assertThat(properties.isSelfHostedAudioUrl(
                "https://prqdlbsazlodierobswv.supabase.co/storage/v1/object/public/musicas/faixa.mp3"
        )).isFalse();
    }

    @Test
    void shouldFailClosedWithoutConfiguredApiOrigin() {
        assertThat(properties.isSelfHostedAudioUrl(
                "https://deefy.olua.me/media/musicas/faixa.mp3"
        )).isFalse();
    }
}
