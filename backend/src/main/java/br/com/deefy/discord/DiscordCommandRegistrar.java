package br.com.deefy.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DiscordCommandRegistrar extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordCommandRegistrar.class);

    private final DiscordBotProperties properties;

    public DiscordCommandRegistrar(DiscordBotProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onReady(ReadyEvent event) {
        JDA jda = event.getJDA();
        String guildId = properties.getTestGuildId().trim();
        Guild guild = jda.getGuildById(guildId);

        if (guild == null) {
            LOGGER.error("Discord bot connected, but configured test guild {} was not found", guildId);
            return;
        }

        guild.upsertCommand(Commands.slash("deefy", "Comandos do Deefy")
                        .addSubcommands(new SubcommandData("ping", "Verifica se o bot está conectado")))
                .queue(
                        command -> LOGGER.info("Discord command /deefy ping registered in test guild {}", guildId),
                        error -> LOGGER.error("Could not register /deefy ping in test guild {}", guildId, error)
                );

        LOGGER.info("Discord bot connected and ready in test guild {}", guildId);
    }
}
