package br.com.deefy.discord;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordPlaybackQueueTest {

    private final DiscordTrack first = new DiscordTrack(1L, "Primeira", "Artista A", "https://deefy.test/a");
    private final DiscordTrack second = new DiscordTrack(2L, "Segunda", "Artista B", "https://deefy.test/b");
    private final DiscordTrack third = new DiscordTrack(3L, "Terceira", "Artista C", "https://deefy.test/c");

    @Test
    void preservesFifoAndUsesOneBasedPositionsForPendingEntries() {
        DiscordPlaybackQueue queue = new DiscordPlaybackQueue(3, 3);
        queue.setCurrent(entry(first));
        assertThat(queue.enqueue(entry(second))).isTrue();
        assertThat(queue.enqueue(entry(third))).isTrue();

        assertThat(queue.pendingAt(1)).contains(entry(second));
        assertThat(queue.pendingAt(2)).contains(entry(third));
        assertThat(queue.advance()).contains(entry(second));
        assertThat(queue.historySnapshot()).containsExactly(entry(first));
    }

    @Test
    void backConsumesHistoryAndPlacesThePreviousCurrentAtQueueFront() {
        DiscordPlaybackQueue queue = new DiscordPlaybackQueue(4, 4);
        queue.setCurrent(entry(first));
        queue.enqueue(entry(second));
        queue.enqueue(entry(third));
        queue.advance();
        queue.advance();

        assertThat(queue.previous()).contains(entry(second));
        assertThat(queue.current()).isEqualTo(entry(second));
        assertThat(queue.pendingSnapshot()).containsExactly(entry(third));
        assertThat(queue.historySnapshot()).containsExactly(entry(first));
        assertThat(queue.previous()).contains(entry(first));
        assertThat(queue.pendingSnapshot()).containsExactly(entry(second), entry(third));
        assertThat(queue.historySnapshot()).isEmpty();
    }

    @Test
    void restartDoesNotChangeQueueOrHistory() {
        DiscordPlaybackQueue queue = new DiscordPlaybackQueue(2, 2);
        queue.setCurrent(entry(first));
        queue.enqueue(entry(second));
        assertThat(queue.restart()).contains(entry(first));
        assertThat(queue.pendingSnapshot()).containsExactly(entry(second));
        assertThat(queue.historySnapshot()).isEmpty();
    }

    @Test
    void queueOperationsDoNotAffectCurrentTrack() {
        DiscordPlaybackQueue queue = new DiscordPlaybackQueue(4, 2);
        queue.setCurrent(entry(first));
        queue.enqueue(entry(second));
        queue.enqueue(entry(third));

        assertThat(queue.move(2, 1)).isTrue();
        assertThat(queue.remove(2)).contains(entry(second));
        queue.clearPending();
        assertThat(queue.current()).isEqualTo(entry(first));
        assertThat(queue.pendingSize()).isZero();
    }

    @Test
    void limitsHistoryAndPendingWithoutDroppingCurrent() {
        DiscordPlaybackQueue queue = new DiscordPlaybackQueue(1, 1);
        queue.setCurrent(entry(first));
        assertThat(queue.enqueue(entry(second))).isTrue();
        assertThat(queue.enqueue(entry(third))).isFalse();
        queue.advance();
        assertThat(queue.historySnapshot()).containsExactly(entry(first));
        assertThat(queue.current()).isEqualTo(entry(second));
    }

    @Test
    void shuffleOnlyChangesPendingEntries() {
        DiscordPlaybackQueue queue = new DiscordPlaybackQueue(3, 2);
        queue.setCurrent(entry(first));
        queue.enqueue(entry(second));
        queue.enqueue(entry(third));
        queue.shuffle(new Random(3));
        assertThat(queue.current()).isEqualTo(entry(first));
        assertThat(queue.pendingSize()).isEqualTo(2);
    }

    private DiscordPlaybackQueue.Entry entry(DiscordTrack track) {
        return new DiscordPlaybackQueue.Entry(track, "tester", "voice");
    }
}
