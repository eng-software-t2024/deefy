package br.com.deefy.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.dave.DaveSessionFactory;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class DiscordBotLifecycle extends ListenerAdapter implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordBotLifecycle.class);

    private final DiscordBotProperties properties;
    private final DiscordCommandListener commandListener;
    private final DiscordCommandRegistrar commandRegistrar;
    private final DiscordPlaybackSessionService playbackSessionService;
    private final DiscordOpusNativeProvider opusNativeProvider;
    private final DiscordDaveSessionFactoryProvider daveSessionFactoryProvider;
    private final DiscordAudioDownloadManager audioDownloadManager;

    private volatile JDA jda;
    private volatile boolean running;

    @Autowired
    public DiscordBotLifecycle(
            DiscordBotProperties properties,
            DiscordCommandListener commandListener,
            DiscordCommandRegistrar commandRegistrar,
            DiscordPlaybackSessionService playbackSessionService,
            DiscordOpusNativeProvider opusNativeProvider,
            DiscordDaveSessionFactoryProvider daveSessionFactoryProvider,
            DiscordAudioDownloadManager audioDownloadManager
    ) {
        this.properties = properties;
        this.commandListener = commandListener;
        this.commandRegistrar = commandRegistrar;
        this.playbackSessionService = playbackSessionService;
        this.opusNativeProvider = opusNativeProvider;
        this.daveSessionFactoryProvider = daveSessionFactoryProvider;
        this.audioDownloadManager = audioDownloadManager;
    }

    public DiscordBotLifecycle(
            DiscordBotProperties properties,
            DiscordCommandListener commandListener,
            DiscordCommandRegistrar commandRegistrar,
            DiscordPlaybackSessionService playbackSessionService,
            DiscordOpusNativeProvider opusNativeProvider,
            DiscordDaveSessionFactoryProvider daveSessionFactoryProvider
    ) {
        this(
                properties,
                commandListener,
                commandRegistrar,
                playbackSessionService,
                opusNativeProvider,
                daveSessionFactoryProvider,
                null
        );
    }

    @Override
    public synchronized void start() {
        if (!properties.isEnabled()) {
            LOGGER.info("Discord integration is disabled");
            return;
        }

        List<String> errors = properties.validationErrors();
        if (!errors.isEmpty()) {
            LOGGER.error("Discord integration is enabled but cannot start: {}", String.join("; ", errors));
            return;
        }

        if (audioDownloadManager != null) {
            try {
                audioDownloadManager.preflight();
            } catch (DiscordAudioPreparationException failure) {
                LOGGER.error("Discord audio cache preflight failed; bot will remain stopped");
                return;
            }
        }

        try {
            String token = properties.resolveToken();
            if (token.isBlank()) {
                LOGGER.error("Discord integration is enabled but the configured token source is empty");
                return;
            }

            DaveSessionFactory daveSessionFactory = initializeRequiredAudioNatives();
            jda = JDABuilder.createDefault(token)
                    .setAudioModuleConfig(new AudioModuleConfig()
                            .withDaveSessionFactory(daveSessionFactory))
                    .addEventListeners(commandListener, commandRegistrar, this)
                    .build();
            LOGGER.info("Discord bot startup requested");
        } catch (IOException exception) {
            jda = null;
            running = false;
            LOGGER.error("Could not read the configured Discord bot token source");
        } catch (RuntimeException exception) {
            jda = null;
            running = false;
            LOGGER.error("Could not initialize Discord bot");
        }
    }

    DaveSessionFactory initializeRequiredAudioNatives() {
        opusNativeProvider.loadRequired();
        return daveSessionFactoryProvider.createRequired();
    }

    @Override
    public void onReady(ReadyEvent event) {
        running = true;
    }

    @Override
    public synchronized void stop() {
        shutdownJda();
    }

    @Override
    public synchronized void stop(Runnable callback) {
        try {
            shutdownJda();
        } finally {
            callback.run();
        }
    }

    private void shutdownJda() {
        playbackSessionService.closeAll();

        JDA currentJda = jda;
        jda = null;
        running = false;

        if (currentJda != null) {
            currentJda.shutdownNow();
            LOGGER.info("Discord bot connection stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
