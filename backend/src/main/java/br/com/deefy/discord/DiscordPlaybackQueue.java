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
final class DiscordPlaybackQueue<T> {

    private final int maxPending;
    private final int maxHistory;
    private final Deque<T> pending = new ArrayDeque<>();
    private final Deque<T> history = new ArrayDeque<>();
    private T current;

    DiscordPlaybackQueue(int maxPending, int maxHistory) {
        if (maxPending < 1 || maxHistory < 1) {
            throw new IllegalArgumentException("queue limits must be positive");
        }
        this.maxPending = maxPending;
        this.maxHistory = maxHistory;
    }

    synchronized void setCurrent(T entry) {
        current = Objects.requireNonNull(entry);
    }

    synchronized T current() {
        return current;
    }

    synchronized boolean enqueue(T entry) {
        Objects.requireNonNull(entry);
        if (pending.size() >= maxPending) {
            return false;
        }
        pending.addLast(entry);
        return true;
    }

    synchronized Optional<T> advance() {
        rememberCurrent();
        current = pending.pollFirst();
        return Optional.ofNullable(current);
    }

    synchronized Optional<T> previous() {
        if (history.isEmpty()) {
            return Optional.empty();
        }
        T previous = history.removeLast();
        if (current != null) {
            if (pending.size() >= maxPending) {
                history.addLast(previous);
                return Optional.empty();
            }
            pending.addFirst(current);
        }
        current = previous;
        return Optional.of(previous);
    }

    synchronized Optional<T> restart() {
        return Optional.ofNullable(current);
    }

    synchronized Optional<T> pendingAt(int publicPosition) {
        if (publicPosition < 1 || publicPosition > pending.size()) {
            return Optional.empty();
        }
        int index = 1;
        for (T entry : pending) {
            if (index++ == publicPosition) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    synchronized Optional<T> remove(int publicPosition) {
        if (publicPosition < 1 || publicPosition > pending.size()) {
            return Optional.empty();
        }
        int index = 1;
        for (var iterator = pending.iterator(); iterator.hasNext(); ) {
            T entry = iterator.next();
            if (index++ == publicPosition) {
                iterator.remove();
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    synchronized boolean move(int publicOrigin, int publicDestination) {
        if (publicOrigin < 1 || publicOrigin > pending.size()
                || publicDestination < 1 || publicDestination > pending.size()) {
            return false;
        }
        if (publicOrigin == publicDestination) {
            return true;
        }
        List<T> entries = new ArrayList<>(pending);
        T moved = entries.remove(publicOrigin - 1);
        entries.add(publicDestination - 1, moved);
        pending.clear();
        pending.addAll(entries);
        return true;
    }

    synchronized void clearPending() {
        pending.clear();
    }

    synchronized void clearAll() {
        pending.clear();
        history.clear();
        current = null;
    }

    synchronized void shuffle(java.util.Random random) {
        List<T> entries = new ArrayList<>(pending);
        Collections.shuffle(entries, Objects.requireNonNull(random));
        pending.clear();
        pending.addAll(entries);
    }

    synchronized List<T> pendingSnapshot() {
        return List.copyOf(pending);
    }

    synchronized List<T> historySnapshot() {
        return List.copyOf(history);
    }

    synchronized int pendingSize() {
        return pending.size();
    }

    synchronized int historySize() {
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
