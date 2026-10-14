package br.com.deefy.discord;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Coalesces public control-message edits and never sends two edits concurrently. */
public final class DiscordPlaybackMessageCoordinator {

    private final MessageTransport transport;
    private long nextRevision;
    private VersionedUpdate pending;
    private boolean editInFlight;

    public DiscordPlaybackMessageCoordinator(MessageTransport transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    public void publish(DiscordPlaybackService.PlaybackPresentation presentation, String sessionId) {
        VersionedUpdate toDispatch;
        synchronized (this) {
            pending = new VersionedUpdate(++nextRevision, presentation, sessionId);
            if (editInFlight) {
                return;
            }
            editInFlight = true;
            toDispatch = pending;
        }
        dispatch(toDispatch);
    }

    private void dispatch(VersionedUpdate update) {
        AtomicBoolean callbackCompleted = new AtomicBoolean();
        Runnable success = () -> {
            if (callbackCompleted.compareAndSet(false, true)) {
                complete(update.revision());
            }
        };
        Consumer<Throwable> failure = ignored -> success.run();
        try {
            transport.edit(
                    update.presentation(),
                    update.sessionId(),
                    success,
                    failure
            );
        } catch (RuntimeException exception) {
            success.run();
        }
    }

    private void complete(long deliveredRevision) {
        VersionedUpdate next = null;
        synchronized (this) {
            if (pending != null && pending.revision() > deliveredRevision) {
                next = pending;
            } else {
                editInFlight = false;
            }
        }
        if (next != null) {
            dispatch(next);
        }
    }

    @FunctionalInterface
    public interface MessageTransport {

        void edit(
                DiscordPlaybackService.PlaybackPresentation presentation,
                String sessionId,
                Runnable onSuccess,
                Consumer<Throwable> onFailure
        );
    }

    private record VersionedUpdate(
            long revision,
            DiscordPlaybackService.PlaybackPresentation presentation,
            String sessionId
    ) {
    }
}
