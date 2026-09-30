package br.com.deefy.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DiscordBotLifecycle extends ListenerAdapter implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordBotLifecycle.class);

    private final DiscordBotProperties properties;
    private final DiscordCommandListener commandListener;
    private final DiscordCommandRegistrar commandRegistrar;

    private volatile JDA jda;
    private volatile boolean running;

    public DiscordBotLifecycle(
            DiscordBotProperties properties,
            DiscordCommandListener commandListener,
            DiscordCommandRegistrar commandRegistrar
    ) {
        this.properties = properties;
        this.commandListener = commandListener;
        this.commandRegistrar = commandRegistrar;
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

        try {
            jda = JDABuilder.createDefault(properties.getToken())
                    .addEventListeners(commandListener, commandRegistrar, this)
                    .build();
            LOGGER.info("Discord bot startup requested");
        } catch (RuntimeException exception) {
            jda = null;
            running = false;
            LOGGER.error("Could not initialize Discord bot", exception);
        }
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
