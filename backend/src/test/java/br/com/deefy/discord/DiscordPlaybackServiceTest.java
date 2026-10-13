package br.com.deefy.discord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DiscordPlaybackServiceTest {

    @Test
    void shouldPresentClearOptionsForAmbiguousTitleWithoutStartingPlayback() {
        DiscordTrackCatalog catalog = mock(DiscordTrackCatalog.class);
        DiscordPlaybackSessionService sessions = mock(DiscordPlaybackSessionService.class);
        when(catalog.find("Luz")).thenReturn(new DiscordTrackCatalog.TrackLookupResult(
                DiscordTrackCatalog.TrackLookupStatus.AMBIGUOUS,
                null,
                List.of(
                        new DiscordTrackCatalog.TrackChoice(1L, "Artista A - Luz", "1"),
                        new DiscordTrackCatalog.TrackChoice(2L, "Artista B - Luz", "2")
                )
        ));

        DiscordPlaybackService.PlaybackCommandResult result = new DiscordPlaybackService(catalog, sessions)
                .play(null, null, "Luz");

        assertThat(result.responseMessage())
                .contains("Artista A - Luz", "Artista B - Luz")
                .contains("Refine usando título - artista");
        verifyNoInteractions(sessions);
    }
}
