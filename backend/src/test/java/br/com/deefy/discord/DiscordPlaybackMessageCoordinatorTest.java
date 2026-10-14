package br.com.deefy.discord;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordPlaybackMessageCoordinatorTest {

    @Test
    void shouldCoalesceUpdatesAndNeverLetAnOlderCompletionOverwriteLatestState() {
        List<Delivery> deliveries = new ArrayList<>();
        DiscordPlaybackMessageCoordinator coordinator = new DiscordPlaybackMessageCoordinator(
                (presentation, sessionId, success, failure) -> deliveries.add(
                        new Delivery(presentation, sessionId, success, failure)
                )
        );

        coordinator.publish(presentation(DiscordPlaybackService.PlaybackMessageState.PREPARING), "session-1");
        coordinator.publish(presentation(DiscordPlaybackService.PlaybackMessageState.PLAYING), "session-1");
        coordinator.publish(presentation(DiscordPlaybackService.PlaybackMessageState.PAUSED), "session-1");

        assertThat(deliveries).hasSize(1);
        deliveries.getFirst().success().run();
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries.get(1).presentation().state())
                .isEqualTo(DiscordPlaybackService.PlaybackMessageState.PAUSED);

        deliveries.getFirst().success().run();
        assertThat(deliveries).hasSize(2);

        coordinator.publish(presentation(DiscordPlaybackService.PlaybackMessageState.STOPPED), "session-1");
        deliveries.get(1).failure().accept(new IllegalStateException("simulated"));

        assertThat(deliveries).hasSize(3);
        assertThat(deliveries.get(2).presentation().state())
                .isEqualTo(DiscordPlaybackService.PlaybackMessageState.STOPPED);
    }

    private DiscordPlaybackService.PlaybackPresentation presentation(
            DiscordPlaybackService.PlaybackMessageState state
    ) {
        return new DiscordPlaybackService.PlaybackPresentation(
                state,
                state.name(),
                "estado público",
                "Faixa",
                "Artista",
                "Pessoa",
                "Canal"
        );
    }

    private record Delivery(
            DiscordPlaybackService.PlaybackPresentation presentation,
            String sessionId,
            Runnable success,
            Consumer<Throwable> failure
    ) {
    }
}
