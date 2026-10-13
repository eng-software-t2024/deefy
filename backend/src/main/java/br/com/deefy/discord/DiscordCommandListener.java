package br.com.deefy.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.springframework.stereotype.Component;

@Component
public class DiscordCommandListener extends ListenerAdapter {

    private static final String ROOT_COMMAND = "deefy";
    private static final String PING_SUBCOMMAND = "ping";
    private static final String JOIN_COMMAND = "entrar";
    private static final String LEAVE_COMMAND = "sair";
    private static final String PLAY_COMMAND = "tocar";
    private static final String MUSIC_OPTION = "musica";

    private final DiscordBotProperties properties;
    private final DiscordVoiceConnectionService voiceConnectionService;
    private final DiscordPlaybackService playbackService;

    public DiscordCommandListener(
            DiscordBotProperties properties,
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordPlaybackService playbackService
    ) {
        this.properties = properties;
        this.voiceConnectionService = voiceConnectionService;
        this.playbackService = playbackService;
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
            DiscordVoiceConnectionService.VoiceCommandResult result = voiceConnectionService.leave(
                    event.getGuild(),
                    event.getMember()
            );
            if (result == DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_STARTED
                    || result == DiscordVoiceConnectionService.VoiceCommandResult.BOT_NOT_CONNECTED) {
                playbackService.stop(event.getGuild());
            }
            replyWithVoiceResult(event, result);
            return;
        }

        if (PLAY_COMMAND.equals(event.getName())) {
            OptionMapping option = event.getOption(MUSIC_OPTION);
            String query = option == null ? "" : option.getAsString();
            DiscordPlaybackService.PlaybackCommandResult result = playbackService.play(
                    event.getGuild(),
                    event.getMember(),
                    query
            );
            event.reply(result.responseMessage())
                    .setEphemeral(true)
                    .queue();
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
