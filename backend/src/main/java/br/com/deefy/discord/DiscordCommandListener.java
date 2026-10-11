package br.com.deefy.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Component
public class DiscordCommandListener extends ListenerAdapter {

    private static final String ROOT_COMMAND = "deefy";
    private static final String PING_SUBCOMMAND = "ping";
    private static final String JOIN_COMMAND = "entrar";
    private static final String LEAVE_COMMAND = "sair";

    private final DiscordBotProperties properties;
    private final DiscordVoiceConnectionService voiceConnectionService;

    public DiscordCommandListener(
            DiscordBotProperties properties,
            DiscordVoiceConnectionService voiceConnectionService
    ) {
        this.properties = properties;
        this.voiceConnectionService = voiceConnectionService;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            return;
        }

        if (!isConfiguredGuild(event)) {
            return;
        }

        if (ROOT_COMMAND.equals(event.getName()) && PING_SUBCOMMAND.equals(event.getSubcommandName())) {
            event.reply("pong").queue();
            return;
        }

        if (JOIN_COMMAND.equals(event.getName())) {
            replyWithVoiceResult(event, voiceConnectionService.join(event.getGuild(), event.getMember()));
            return;
        }

        if (LEAVE_COMMAND.equals(event.getName())) {
            replyWithVoiceResult(event, voiceConnectionService.leave(event.getGuild(), event.getMember()));
        }
    }

    private void replyWithVoiceResult(
            SlashCommandInteractionEvent event,
            DiscordVoiceConnectionService.VoiceCommandResult result
    ) {
        event.reply(result.responseMessage())
                .setEphemeral(true)
                .queue();
    }

    private boolean isConfiguredGuild(SlashCommandInteractionEvent event) {
        return event.getGuild() != null
                && properties.getTestGuildId() != null
                && event.getGuild().getId().equals(properties.getTestGuildId().trim());
    }
}
