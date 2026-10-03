package br.com.deefy.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Component
public class DiscordCommandListener extends ListenerAdapter {

    private static final String ROOT_COMMAND = "deefy";
    private static final String PING_SUBCOMMAND = "ping";

    private final DiscordBotProperties properties;

    public DiscordCommandListener(DiscordBotProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            return;
        }

        if (isConfiguredGuild(event)
                && ROOT_COMMAND.equals(event.getName())
                && PING_SUBCOMMAND.equals(event.getSubcommandName())) {
            event.reply("pong").queue();
        }
    }

    private boolean isConfiguredGuild(SlashCommandInteractionEvent event) {
        return event.getGuild() != null
                && properties.getTestGuildId() != null
                && event.getGuild().getId().equals(properties.getTestGuildId().trim());
    }
}
