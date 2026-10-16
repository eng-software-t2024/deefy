package br.com.deefy.discord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Coalesces public control-message edits and never sends two edits concurrently. */
public final class DiscordPlaybackMessageCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordPlaybackMessageCoordinator.class);

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
            VersionedUpdate previous = pending;
            pending = new VersionedUpdate(++nextRevision, presentation, sessionId);
            if (editInFlight) {
                LOGGER.info(
                        "Discord playback message update coalesced: revision={}, previousRevision={}, state={}, "
                                + "sessionRef={}, controls={}",
                        pending.revision(),
                        previous == null ? "none" : previous.revision(),
                        presentation.state(),
                        DiscordDiagnosticSupport.pseudonymize(sessionId),
                        controlsFor(presentation)
                );
                return;
            }
            editInFlight = true;
            toDispatch = pending;
        }
        dispatch(toDispatch);
    }

    private void dispatch(VersionedUpdate update) {
        LOGGER.info(
                "Discord playback message update dispatched: revision={}, state={}, sessionRef={}, controls={}",
                update.revision(),
                update.presentation().state(),
                DiscordDiagnosticSupport.pseudonymize(update.sessionId()),
                controlsFor(update.presentation())
        );
        AtomicBoolean callbackCompleted = new AtomicBoolean();
        Runnable success = () -> {
            if (callbackCompleted.compareAndSet(false, true)) {
                complete(update.revision());
            }
        };
        Consumer<Throwable> failure = error -> {
            if (callbackCompleted.compareAndSet(false, true)) {
                LOGGER.warn(
                        "Discord playback message edit completed: revision={}, state={}, sessionRef={}, result=failure, errorType={}",
                        update.revision(),
                        update.presentation().state(),
                        DiscordDiagnosticSupport.pseudonymize(update.sessionId()),
                        error == null ? "unknown" : error.getClass().getSimpleName()
                );
                complete(update.revision());
            }
        };
        Runnable completedSuccessfully = () -> {
            if (callbackCompleted.compareAndSet(false, true)) {
                LOGGER.info(
                        "Discord playback message edit completed: revision={}, state={}, sessionRef={}, result=success",
                        update.revision(),
                        update.presentation().state(),
                        DiscordDiagnosticSupport.pseudonymize(update.sessionId())
                );
                complete(update.revision());
            }
        };
        try {
            transport.edit(
                    update.presentation(),
                    update.sessionId(),
                    completedSuccessfully,
                    failure
            );
        } catch (RuntimeException exception) {
            if (callbackCompleted.compareAndSet(false, true)) {
                LOGGER.warn(
                        "Discord playback message edit completed: revision={}, state={}, sessionRef={}, result=failure, errorType={}",
                        update.revision(),
                        update.presentation().state(),
                        DiscordDiagnosticSupport.pseudonymize(update.sessionId()),
                        exception.getClass().getSimpleName()
                );
                complete(update.revision());
            }
        }
    }

    private void complete(long deliveredRevision) {
        VersionedUpdate next = null;
        synchronized (this) {
            if (pending != null && pending.revision() > deliveredRevision) {
                LOGGER.info(
                        "Discord playback message update superseded: revision={}, replacementRevision={}",
                        deliveredRevision,
                        pending.revision()
                );
                next = pending;
            } else {
                editInFlight = false;
            }
        }
        if (next != null) {
            dispatch(next);
        }
    }

    private String controlsFor(DiscordPlaybackService.PlaybackPresentation presentation) {
        if (presentation == null || presentation.state() == null) {
            return "none";
        }
        String queueControls = "skip=" + (presentation.pendingCount() > 0 ? "enabled" : "disabled")
                + ",back=" + (presentation.historySize() > 0 ? "enabled" : "disabled")
                + ",restart=enabled";
        return switch (presentation.state()) {
            case PREPARING -> "pause=disabled,stop=enabled," + queueControls;
            case PLAYING -> "pause=enabled,stop=enabled," + queueControls;
            case PAUSED -> "resume=enabled,stop=enabled," + queueControls;
            case COMPLETED, STOPPED, FAILED -> "none";
        };
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
