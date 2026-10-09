package br.com.deefy.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
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

        register(guild, guildId, Commands.slash("deefy", "Comandos do Deefy")
                .addSubcommands(new SubcommandData("ping", "Verifica se o bot está conectado")));
        register(guild, guildId, Commands.slash("entrar", "Conecta o bot ao seu canal de voz"));
        register(guild, guildId, Commands.slash("sair", "Desconecta o bot do canal de voz"));

        LOGGER.info("Discord bot connected and ready in test guild {}", guildId);
    }

    private void register(Guild guild, String guildId, CommandData command) {
        guild.upsertCommand(command)
                .queue(
                        registered -> LOGGER.info(
                                "Discord command /{} registered in test guild {}",
                                command.getName(),
                                guildId
                        ),
                        error -> LOGGER.error(
                                "Could not register Discord command /{} in test guild {}",
                                command.getName(),
                                guildId
                        )
                );
    }
}
