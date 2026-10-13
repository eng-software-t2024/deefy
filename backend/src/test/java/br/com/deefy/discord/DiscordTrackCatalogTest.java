package br.com.deefy.discord;

import br.com.deefy.model.Artist;
import br.com.deefy.model.Music;
import br.com.deefy.repository.MusicRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscordTrackCatalogTest {

    private final MusicRepository repository = mock(MusicRepository.class);
    private final DiscordPlaybackProperties properties = new DiscordPlaybackProperties();
    private final DiscordTrackCatalog catalog = new DiscordTrackCatalog(repository, properties);

    @BeforeEach
    void configureSelfHostedOrigin() {
        properties.setApiUrl("https://deefy.olua.me");
    }

    @Test
    void shouldFindTrackByExactPositiveId() {
        Music music = music(13L, "Faixa local");
        when(repository.findWithArtistById(13L)).thenReturn(Optional.of(music));

        DiscordTrackCatalog.TrackLookupResult result = catalog.find("13");

        assertThat(result.status()).isEqualTo(DiscordTrackCatalog.TrackLookupStatus.FOUND);
        assertThat(result.track().id()).isEqualTo(13L);
        assertThat(result.track().audioUrl()).startsWith("https://deefy.olua.me/");
    }

    @Test
    void shouldNormalizeWhitespaceAndCaseForTitleLookup() {
        Music music = music(7L, "Nome exato");
        when(repository.findByTitleIgnoreCase("nome exato")).thenReturn(List.of(music));

        DiscordTrackCatalog.TrackLookupResult result = catalog.find("  nome   exato  ");

        assertThat(result.status()).isEqualTo(DiscordTrackCatalog.TrackLookupStatus.FOUND);
        assertThat(result.track().id()).isEqualTo(7L);
    }

    @Test
    void shouldFindSinglePartialTitleMatch() {
        Music music = music(4L, "Uma faixa local");
        when(repository.findByTitleIgnoreCase("faixa")).thenReturn(List.of());
        when(repository.findByTitleContainingIgnoreCase(eq("faixa"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(music)));

        assertThat(catalog.find("faixa").status())
                .isEqualTo(DiscordTrackCatalog.TrackLookupStatus.FOUND);
    }

    @Test
    void shouldRejectMissingAndAmbiguousTracks() {
        Music first = music(1L, "Faixa");
        Music second = music(2L, "Faixa");
        when(repository.findWithArtistById(999L)).thenReturn(Optional.empty());
        when(repository.findByTitleIgnoreCase("Faixa")).thenReturn(List.of(first, second));

        assertThat(catalog.find("999").status())
                .isEqualTo(DiscordTrackCatalog.TrackLookupStatus.NOT_FOUND);
        assertThat(catalog.find("Faixa").status())
                .isEqualTo(DiscordTrackCatalog.TrackLookupStatus.AMBIGUOUS);
        assertThat(catalog.find("  ").status())
                .isEqualTo(DiscordTrackCatalog.TrackLookupStatus.INVALID_QUERY);
    }

    @Test
    void shouldRejectMissingAudioUrlAndSourcesOutsideSelfHostedOrigin() {
        Music missingUrl = music(5L, "Sem arquivo");
        missingUrl.setFileUrl(" ");
        Music external = music(6L, "Origem externa");
        external.setFileUrl("https://outro-host.example/media/faixa.mp3");
        Music legacy = music(7L, "Origem antiga");
        legacy.setFileUrl(
                "https://prqdlbsazlodierobswv.supabase.co/storage/v1/object/public/musicas/faixa.mp3"
        );
        when(repository.findWithArtistById(5L)).thenReturn(Optional.of(missingUrl));
        when(repository.findWithArtistById(6L)).thenReturn(Optional.of(external));
        when(repository.findWithArtistById(7L)).thenReturn(Optional.of(legacy));

        assertThat(catalog.find("5").status())
                .isEqualTo(DiscordTrackCatalog.TrackLookupStatus.MISSING_AUDIO_URL);
        assertThat(catalog.find("6").status())
                .isEqualTo(DiscordTrackCatalog.TrackLookupStatus.UNSAFE_SOURCE);
        assertThat(catalog.find("7").status())
                .isEqualTo(DiscordTrackCatalog.TrackLookupStatus.UNSAFE_SOURCE);
    }

    private Music music(long id, String title) {
        Music music = new Music(id, title, "Rock", 180, new Artist(1L, "Artista"));
        music.setFileUrl("https://deefy.olua.me/media/musicas/" + id + ".mp3");
        return music;
    }
}
