package br.com.deefy.discord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Mutable queue state owned by one guild lane. It never performs I/O and is
 * therefore safe to exercise deterministically without Discord or audio.
 */
final class DiscordPlaybackQueue {

    private final int maxPending;
    private final int maxHistory;
    private final Deque<Entry> pending = new ArrayDeque<>();
    private final Deque<Entry> history = new ArrayDeque<>();
    private Entry current;

    DiscordPlaybackQueue(int maxPending, int maxHistory) {
        if (maxPending < 1 || maxHistory < 1) {
            throw new IllegalArgumentException("queue limits must be positive");
        }
        this.maxPending = maxPending;
        this.maxHistory = maxHistory;
    }

    void setCurrent(Entry entry) {
        current = Objects.requireNonNull(entry);
    }

    Entry current() {
        return current;
    }

    boolean enqueue(Entry entry) {
        Objects.requireNonNull(entry);
        if (pending.size() >= maxPending) {
            return false;
        }
        pending.addLast(entry);
        return true;
    }

    Optional<Entry> advance() {
        rememberCurrent();
        current = pending.pollFirst();
        return Optional.ofNullable(current);
    }

    Optional<Entry> previous() {
        if (history.isEmpty()) {
            return Optional.empty();
        }
        Entry previous = history.removeLast();
        if (current != null) {
            if (pending.size() >= maxPending) {
                pending.removeLast();
            }
            pending.addFirst(current);
        }
        current = previous;
        return Optional.of(previous);
    }

    Optional<Entry> restart() {
        return Optional.ofNullable(current);
    }

    Optional<Entry> pendingAt(int publicPosition) {
        if (publicPosition < 1 || publicPosition > pending.size()) {
            return Optional.empty();
        }
        int index = 1;
        for (Entry entry : pending) {
            if (index++ == publicPosition) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    Optional<Entry> remove(int publicPosition) {
        if (publicPosition < 1 || publicPosition > pending.size()) {
            return Optional.empty();
        }
        int index = 1;
        for (var iterator = pending.iterator(); iterator.hasNext(); ) {
            Entry entry = iterator.next();
            if (index++ == publicPosition) {
                iterator.remove();
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    boolean move(int publicOrigin, int publicDestination) {
        if (publicOrigin < 1 || publicOrigin > pending.size()
                || publicDestination < 1 || publicDestination > pending.size()) {
            return false;
        }
        if (publicOrigin == publicDestination) {
            return true;
        }
        List<Entry> entries = new ArrayList<>(pending);
        Entry moved = entries.remove(publicOrigin - 1);
        entries.add(publicDestination - 1, moved);
        pending.clear();
        pending.addAll(entries);
        return true;
    }

    void clearPending() {
        pending.clear();
    }

    void clearAll() {
        pending.clear();
        history.clear();
        current = null;
    }

    void shuffle(java.util.Random random) {
        List<Entry> entries = new ArrayList<>(pending);
        Collections.shuffle(entries, Objects.requireNonNull(random));
        pending.clear();
        pending.addAll(entries);
    }

    List<Entry> pendingSnapshot() {
        return List.copyOf(pending);
    }

    List<Entry> historySnapshot() {
        return List.copyOf(history);
    }

    int pendingSize() {
        return pending.size();
    }

    int historySize() {
        return history.size();
    }

    private void rememberCurrent() {
        if (current == null) {
            return;
        }
        history.addLast(current);
        while (history.size() > maxHistory) {
            history.removeFirst();
        }
    }

    record Entry(DiscordTrack track, String requester, String voiceChannel) {
        Entry {
            Objects.requireNonNull(track);
        }
    }
}
