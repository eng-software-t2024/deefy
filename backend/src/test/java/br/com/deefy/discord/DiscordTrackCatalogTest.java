package br.com.deefy.discord;

import br.com.deefy.model.Artist;
import br.com.deefy.model.Music;
import br.com.deefy.repository.MusicRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    void shouldFindTrackByExactPositiveIdAsTechnicalAlternative() {
        Music music = music(13L, "Faixa local", "Artista");
        when(repository.findWithArtistById(13L)).thenReturn(Optional.of(music));

        DiscordTrackCatalog.TrackLookupResult result = catalog.find("13");

        assertThat(result.status()).isEqualTo(DiscordTrackCatalog.TrackLookupStatus.FOUND);
        assertThat(result.track().id()).isEqualTo(13L);
        assertThat(result.track().audioUrl()).startsWith("https://deefy.olua.me/");
    }

    @Test
    void shouldNormalizeAccentsCaseAndWhitespaceForTitle() {
        Music music = music(7L, "Matuê - ÍCONE FASHION", "30PRAUM");
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(music));

        DiscordTrackCatalog.TrackLookupResult result = catalog.find("  icone   fashion  ");

        assertThat(result.status()).isEqualTo(DiscordTrackCatalog.TrackLookupStatus.FOUND);
        assertThat(result.track().id()).isEqualTo(7L);
    }

    @Test
    void shouldResolveRealImportedTitleAndCreditedArtistContract() {
        Music xtranho = music(369L, "Matuê, BRANDÃO85 - XTRANHO", "30PRAUM");
        Music another = music(370L, "Outro Artista - XTRANHO", "30PRAUM");
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(xtranho, another));

        DiscordTrackCatalog.TrackLookupResult result = catalog.find("XTRANHO - Matue");

        assertThat(result.status()).isEqualTo(DiscordTrackCatalog.TrackLookupStatus.FOUND);
        assertThat(result.track().id()).isEqualTo(369L);
    }

    @Test
    void shouldNotTreatEveryHyphenInsideTitleAsMandatoryArtistSeparator() {
        Music music = music(8L, "Amor - Ódio", "Banda");
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(music));

        DiscordTrackCatalog.TrackLookupResult result = catalog.find("AMOR - odio");

        assertThat(result.status()).isEqualTo(DiscordTrackCatalog.TrackLookupStatus.FOUND);
        assertThat(result.track().id()).isEqualTo(8L);
    }

    @Test
    void shouldReturnClearChoicesInsteadOfSelectingAmbiguousTitle() {
        Music first = music(1L, "Artista A - Luz", "Coletivo");
        Music second = music(2L, "Artista B - Luz", "Coletivo");
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(first, second));

        DiscordTrackCatalog.TrackLookupResult result = catalog.find("Luz");

        assertThat(result.status()).isEqualTo(DiscordTrackCatalog.TrackLookupStatus.AMBIGUOUS);
        assertThat(result.track()).isNull();
        assertThat(result.choices())
                .extracting(DiscordTrackCatalog.TrackChoice::label)
                .containsExactly("Artista A - Luz — Coletivo", "Artista B - Luz — Coletivo");
    }

    @Test
    void shouldReportMissingTrackAndInvalidQuery() {
        when(repository.findWithArtistById(999L)).thenReturn(Optional.empty());
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of());

        assertThat(catalog.find("999").status())
                .isEqualTo(DiscordTrackCatalog.TrackLookupStatus.NOT_FOUND);
        assertThat(catalog.find("Faixa inexistente").status())
                .isEqualTo(DiscordTrackCatalog.TrackLookupStatus.NOT_FOUND);
        assertThat(catalog.find("  ").status())
                .isEqualTo(DiscordTrackCatalog.TrackLookupStatus.INVALID_QUERY);
    }

    @Test
    void shouldRejectMissingAudioUrlAndSourcesOutsideSelfHostedOrigin() {
        Music missingUrl = music(5L, "Sem arquivo", "Artista");
        missingUrl.setFileUrl(" ");
        Music external = music(6L, "Origem externa", "Artista");
        external.setFileUrl("https://outro-host.example/media/faixa.mp3");
        Music legacy = music(7L, "Origem antiga", "Artista");
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

    @Test
    void shouldAutocompleteUsingTitleOrCreditedArtistWithoutLeakingAudioUrl() {
        Music xtranho = music(369L, "Matuê, BRANDÃO85 - XTRANHO", "30PRAUM");
        Music another = music(370L, "Matuê - REI TUÊ", "30PRAUM");
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(xtranho, another));

        List<DiscordTrackCatalog.TrackChoice> suggestions = catalog.suggest("matue");

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions)
                .extracting(DiscordTrackCatalog.TrackChoice::value)
                .containsExactly("369", "370");
        assertThat(suggestions)
                .allSatisfy(choice -> assertThat(choice.label()).doesNotContain("https://"));
    }

    private Music music(long id, String title, String artist) {
        Music music = new Music(id, title, "Rock", 180, new Artist(1L, artist));
        music.setFileUrl("https://deefy.olua.me/media/musicas/" + id + ".mp3");
        return music;
    }
}
