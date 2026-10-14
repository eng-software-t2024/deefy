package br.com.deefy.discord;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @SuppressWarnings("unchecked")
    @Test
    void shouldExposeRealProgressAndTimeoutWithoutClaimingAudioWasHeard() {
        DiscordTrackCatalog catalog = mock(DiscordTrackCatalog.class);
        DiscordPlaybackSessionService sessions = mock(DiscordPlaybackSessionService.class);
        Guild guild = mock(Guild.class);
        Member member = mock(Member.class);
        DiscordTrack track = new DiscordTrack(369L, "XTRANHO", "Matuê", "https://deefy.olua.me/media/369");
        ArgumentCaptor<Consumer<DiscordPlaybackSessionService.PlaybackProgress>> observer =
                ArgumentCaptor.forClass(Consumer.class);
        when(catalog.find("XTRANHO - Matuê")).thenReturn(new DiscordTrackCatalog.TrackLookupResult(
                DiscordTrackCatalog.TrackLookupStatus.FOUND,
                track,
                List.of()
        ));
        when(sessions.start(eq(guild), eq(member), eq(track), observer.capture()))
                .thenReturn(DiscordPlaybackSessionService.PlaybackStartResult.STARTED);
        List<String> updates = new ArrayList<>();

        DiscordPlaybackService.PlaybackCommandResult result = new DiscordPlaybackService(catalog, sessions)
                .play(guild, member, "XTRANHO - Matuê", update -> updates.add(update.responseMessage()));
        observer.getValue().accept(DiscordPlaybackSessionService.PlaybackProgress.AUDIO_FRAMES_AVAILABLE);
        observer.getValue().accept(DiscordPlaybackSessionService.PlaybackProgress.START_TIMEOUT);

        assertThat(result.awaitingProgress()).isTrue();
        assertThat(updates).containsExactly(
                "O bot começou a fornecer quadros de áudio ao mecanismo de voz; "
                        + "isso não confirma a audição no cliente.",
                "A reprodução não começou dentro do tempo esperado e foi cancelada."
        );
    }
}
