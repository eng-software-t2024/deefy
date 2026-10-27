package br.com.deefy.discord;

import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Publishes one account-wide bot presence from the latest per-guild playback state. */
@Component
public class DiscordPresenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordPresenceService.class);
    private static final String GENERIC_ACTIVITY = "Deefy • música em reprodução";
    private static final int MAX_TITLE_LENGTH = 96;

    private final Executor publishExecutor;
    private final ExecutorService ownedExecutor;
    private final PresencePublisher publisher;
    private final Object monitor = new Object();
    private final Map<Long, GuildPresence> activeGuilds = new HashMap<>();

    private JDA jda;
    private long revision;
    private boolean publishScheduled;

    @Autowired
    public DiscordPresenceService() {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "deefy-discord-presence");
            thread.setDaemon(true);
            return thread;
        });
        this.publishExecutor = executor;
        this.ownedExecutor = executor;
        this.publisher = (client, status, activity) -> client.getPresence().setPresence(status, activity);
    }

    DiscordPresenceService(Executor publishExecutor, PresencePublisher publisher) {
        this.publishExecutor = Objects.requireNonNull(publishExecutor);
        this.ownedExecutor = null;
        this.publisher = Objects.requireNonNull(publisher);
    }

    public void configure(JDA client) {
        synchronized (monitor) {
            jda = client;
            revision++;
        }
        schedulePublish();
    }

    public void onPlaybackProgress(
            long guildId,
            DiscordTrack track,
            DiscordPlaybackSessionService.PlaybackProgress progress
    ) {
        if (track == null || progress == null) {
            return;
        }
        synchronized (monitor) {
            switch (progress) {
                case AUDIO_FRAMES_AVAILABLE, RESUMED -> activeGuilds.put(
                        guildId,
                        new GuildPresence(safeTitle(track.title()), PlaybackMode.PLAYING)
                );
                case PAUSED -> activeGuilds.put(
                        guildId,
                        new GuildPresence(safeTitle(track.title()), PlaybackMode.PAUSED)
                );
                case FAILED, COMPLETED, STOPPED, PAUSE_TIMEOUT, START_TIMEOUT ->
                        activeGuilds.remove(guildId);
                case VOICE_CONNECTED -> {
                    // The track is not playing yet; retain the last authoritative state.
                }
            }
            revision++;
        }
        schedulePublish();
    }

    public void clearAll() {
        synchronized (monitor) {
            activeGuilds.clear();
            revision++;
        }
        schedulePublish();
    }

    @PreDestroy
    public void shutdown() {
        clearAll();
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    private void schedulePublish() {
        synchronized (monitor) {
            if (publishScheduled) {
                return;
            }
            publishScheduled = true;
        }
        try {
            publishExecutor.execute(this::publishLatest);
        } catch (RuntimeException rejected) {
            synchronized (monitor) {
                publishScheduled = false;
            }
            LOGGER.warn("Could not schedule Discord presence update ({})", rejected.getClass().getSimpleName());
        }
    }

    private void publishLatest() {
        long publishedRevision;
        JDA client;
        PresenceSnapshot snapshot;
        synchronized (monitor) {
            publishedRevision = revision;
            client = jda;
            snapshot = snapshotLocked();
        }

        if (client != null) {
            try {
                publisher.publish(client, OnlineStatus.ONLINE, activity(snapshot));
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not update Discord presence ({})", failure.getClass().getSimpleName());
            }
        }

        boolean reschedule;
        synchronized (monitor) {
            reschedule = revision != publishedRevision;
            if (!reschedule) {
                publishScheduled = false;
            }
        }
        if (reschedule) {
            try {
                publishExecutor.execute(this::publishLatest);
            } catch (RuntimeException rejected) {
                synchronized (monitor) {
                    publishScheduled = false;
                }
                LOGGER.warn("Could not reschedule Discord presence update ({})", rejected.getClass().getSimpleName());
            }
        }
    }

    private PresenceSnapshot snapshotLocked() {
        if (activeGuilds.isEmpty()) {
            return new PresenceSnapshot(PresenceMode.NEUTRAL, null);
        }
        if (activeGuilds.size() > 1) {
            return new PresenceSnapshot(PresenceMode.MULTIPLE, null);
        }
        GuildPresence presence = activeGuilds.values().iterator().next();
        PresenceMode mode = presence.mode() == PlaybackMode.PAUSED
                ? PresenceMode.PAUSED
                : PresenceMode.PLAYING;
        return new PresenceSnapshot(mode, presence.title());
    }

    private Activity activity(PresenceSnapshot snapshot) {
        return switch (snapshot.mode()) {
            case NEUTRAL -> null;
            case MULTIPLE -> Activity.playing(GENERIC_ACTIVITY);
            case PLAYING -> Activity.playing("Deefy • " + snapshot.title());
            case PAUSED -> Activity.playing("Deefy • pausada: " + snapshot.title());
        };
    }

    private String safeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "faixa atual";
        }
        String normalized = title.replaceAll("[\\p{Cntrl}\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.length() <= MAX_TITLE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_TITLE_LENGTH - 1).trim() + "…";
    }

    @FunctionalInterface
    interface PresencePublisher {
        void publish(JDA jda, OnlineStatus status, Activity activity);
    }

    private enum PlaybackMode {
        PLAYING,
        PAUSED
    }

    private enum PresenceMode {
        NEUTRAL,
        PLAYING,
        PAUSED,
        MULTIPLE
    }

    private record GuildPresence(String title, PlaybackMode mode) {
    }

    private record PresenceSnapshot(PresenceMode mode, String title) {
    }
}
