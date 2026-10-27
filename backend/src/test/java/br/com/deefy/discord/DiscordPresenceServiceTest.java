package br.com.deefy.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class DiscordPresenceServiceTest {

    @Test
    void shouldClearPresenceWhenNoGuildIsPlayingAndShowTitleForOneGuild() {
        List<PublishedPresence> published = new ArrayList<>();
        DiscordPresenceService service = service(published, Runnable::run);
        DiscordTrack track = new DiscordTrack(1L, "Brandão - CEO", "30PRAUM", "self-hosted");

        service.configure(mock(JDA.class));
        service.onPlaybackProgress(
                10L,
                track,
                DiscordPlaybackSessionService.PlaybackProgress.AUDIO_FRAMES_AVAILABLE
        );
        service.onPlaybackProgress(
                10L,
                track,
                DiscordPlaybackSessionService.PlaybackProgress.COMPLETED
        );

        assertThat(published.get(0).activity()).isNull();
        assertThat(published.get(1).activity().getName()).contains("Brandão - CEO");
        assertThat(published.get(2).activity()).isNull();
        assertThat(published).allSatisfy(value -> assertThat(value.status()).isEqualTo(OnlineStatus.ONLINE));
    }

    @Test
    void shouldIndicatePauseAndUseGenericActivityForMultipleGuilds() {
        List<PublishedPresence> published = new ArrayList<>();
        DiscordPresenceService service = service(published, Runnable::run);
        DiscordTrack first = new DiscordTrack(1L, "Faixa A", "Artista A", "self-hosted");
        DiscordTrack second = new DiscordTrack(2L, "Faixa B", "Artista B", "self-hosted");

        service.configure(mock(JDA.class));
        service.onPlaybackProgress(10L, first, DiscordPlaybackSessionService.PlaybackProgress.AUDIO_FRAMES_AVAILABLE);
        service.onPlaybackProgress(10L, first, DiscordPlaybackSessionService.PlaybackProgress.PAUSED);
        assertThat(published.get(2).activity().getName()).contains("pausada", "Faixa A");

        service.onPlaybackProgress(20L, second, DiscordPlaybackSessionService.PlaybackProgress.AUDIO_FRAMES_AVAILABLE);
        assertThat(published.get(3).activity().getName()).isEqualTo("Deefy • música em reprodução");

        service.onPlaybackProgress(20L, second, DiscordPlaybackSessionService.PlaybackProgress.STOPPED);
        assertThat(published.get(4).activity().getName()).contains("Faixa A");
    }

    @Test
    void shouldPublishOnlyTheLatestSnapshotWhenUpdatesAreQueued() {
        List<PublishedPresence> published = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        DiscordPresenceService service = service(published, executor);
        DiscordTrack track = new DiscordTrack(1L, "Faixa antiga", "Artista", "self-hosted");
        JDA jda = mock(JDA.class);

        service.configure(jda);
        service.onPlaybackProgress(10L, track, DiscordPlaybackSessionService.PlaybackProgress.AUDIO_FRAMES_AVAILABLE);
        service.onPlaybackProgress(10L, track, DiscordPlaybackSessionService.PlaybackProgress.PAUSED);
        executor.runNext();

        assertThat(published).hasSize(1);
        assertThat(published.get(0).activity().getName()).contains("pausada", "Faixa antiga");
    }

    @Test
    void shouldContainPublisherFailureAndContinueWithTheLatestState() {
        List<PublishedPresence> published = new ArrayList<>();
        Executor direct = Runnable::run;
        DiscordPresenceService service = new DiscordPresenceService(
                direct,
                (jda, status, activity) -> {
                    if (activity != null && activity.getName().contains("Falha")) {
                        throw new IllegalStateException("simulated publisher failure");
                    }
                    published.add(new PublishedPresence(status, activity));
                }
        );
        JDA jda = mock(JDA.class);
        DiscordTrack failed = new DiscordTrack(1L, "Falha", "Artista", "self-hosted");
        DiscordTrack recovered = new DiscordTrack(2L, "Recuperada", "Artista", "self-hosted");

        service.configure(jda);
        assertThatCode(() -> service.onPlaybackProgress(
                10L,
                failed,
                DiscordPlaybackSessionService.PlaybackProgress.AUDIO_FRAMES_AVAILABLE
        )).doesNotThrowAnyException();
        assertThatCode(() -> service.onPlaybackProgress(
                10L,
                recovered,
                DiscordPlaybackSessionService.PlaybackProgress.AUDIO_FRAMES_AVAILABLE
        )).doesNotThrowAnyException();

        assertThat(published).extracting(value -> value.activity())
                .filteredOn(Objects::nonNull)
                .extracting(Activity::getName)
                .contains("Deefy • Recuperada");
    }

    private DiscordPresenceService service(List<PublishedPresence> published, Executor executor) {
        return new DiscordPresenceService(
                executor,
                (jda, status, activity) -> published.add(new PublishedPresence(status, activity))
        );
    }

    private record PublishedPresence(OnlineStatus status, Activity activity) {
    }

    private static final class ManualExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.remove(0).run();
        }
    }
}
