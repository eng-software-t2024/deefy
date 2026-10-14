package br.com.deefy.discord;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
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
    void shouldNotTouchPlaybackStateForInvalidRequest() {
        DiscordTrackCatalog catalog = mock(DiscordTrackCatalog.class);
        DiscordPlaybackSessionService sessions = mock(DiscordPlaybackSessionService.class);
        when(catalog.find("desconhecida")).thenReturn(new DiscordTrackCatalog.TrackLookupResult(
                DiscordTrackCatalog.TrackLookupStatus.NOT_FOUND,
                null,
                List.of()
        ));

        DiscordPlaybackService.PlaybackCommandResult result = new DiscordPlaybackService(catalog, sessions)
                .play(null, null, "desconhecida");

        assertThat(result.presentation().description()).contains("Não encontrei");
        verifyNoInteractions(sessions);
    }

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

        assertThat(result.presentation().description())
                .contains("Artista A - Luz", "Artista B - Luz")
                .contains("Refine usando título - artista");
        verifyNoInteractions(sessions);
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldExposeReadablePlaybackProgressWithoutTechnicalDetails() {
        DiscordTrackCatalog catalog = mock(DiscordTrackCatalog.class);
        DiscordPlaybackSessionService sessions = mock(DiscordPlaybackSessionService.class);
        Guild guild = mock(Guild.class);
        Member member = mock(Member.class);
        GuildVoiceState voiceState = mock(GuildVoiceState.class);
        AudioChannelUnion voiceChannel = mock(AudioChannelUnion.class);
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
        when(member.getEffectiveName()).thenReturn("Pessoa de teste");
        when(member.getVoiceState()).thenReturn(voiceState);
        when(voiceState.getChannel()).thenReturn(voiceChannel);
        when(voiceChannel.getName()).thenReturn("Música");
        List<DiscordPlaybackService.PlaybackPresentation> updates = new ArrayList<>();

        DiscordPlaybackService.PlaybackCommandResult result = new DiscordPlaybackService(catalog, sessions)
                .play(guild, member, "XTRANHO - Matuê", update -> updates.add(update.presentation()));
        observer.getValue().accept(DiscordPlaybackSessionService.PlaybackProgress.AUDIO_FRAMES_AVAILABLE);
        observer.getValue().accept(DiscordPlaybackSessionService.PlaybackProgress.START_TIMEOUT);

        assertThat(result.awaitingProgress()).isTrue();
        assertThat(result.presentation().trackTitle()).isEqualTo("XTRANHO");
        assertThat(result.presentation().artist()).isEqualTo("Matuê");
        assertThat(result.presentation().requester()).isEqualTo("Pessoa de teste");
        assertThat(result.presentation().voiceChannel()).isEqualTo("Música");
        assertThat(updates).extracting(DiscordPlaybackService.PlaybackPresentation::heading)
                .containsExactly("Tocando agora", "Não foi possível reproduzir");
        assertThat(updates)
                .extracting(DiscordPlaybackService.PlaybackPresentation::description)
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain("PCM", "frame", "DAVE", "codec", "audição no cliente"));
    }
}
