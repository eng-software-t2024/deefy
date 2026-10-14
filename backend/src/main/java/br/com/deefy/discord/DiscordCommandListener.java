package br.com.deefy.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.springframework.stereotype.Component;

import java.util.List;

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

        if (!isAllowedGuild(event.getGuild() == null ? null : event.getGuild().getId())) {
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
            event.reply("Preparando a faixa e a conexão de voz.")
                    .setEphemeral(true)
                    .queue(hook -> {
                        try {
                            DiscordPlaybackService.PlaybackCommandResult result = playbackService.play(
                                    event.getGuild(),
                                    event.getMember(),
                                    query,
                                    progress -> hook.editOriginal(progress.responseMessage()).queue()
                            );
                            if (!result.awaitingProgress()) {
                                hook.editOriginal(result.responseMessage()).queue();
                            }
                        } catch (RuntimeException ignored) {
                            hook.editOriginal("Não consegui iniciar a reprodução e a sessão foi cancelada.").queue();
                        }
                    });
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.isFromGuild()
                || !isAllowedGuild(event.getGuild() == null ? null : event.getGuild().getId())
                || !PLAY_COMMAND.equals(event.getName())
                || !MUSIC_OPTION.equals(event.getFocusedOption().getName())) {
            return;
        }

        List<Command.Choice> choices = playbackService.suggest(event.getFocusedOption().getValue()).stream()
                .map(suggestion -> new Command.Choice(suggestion.label(), suggestion.value()))
                .toList();
        event.replyChoices(choices).queue();
    }

    private void replyWithVoiceResult(
            SlashCommandInteractionEvent event,
            DiscordVoiceConnectionService.VoiceCommandResult result
    ) {
        event.reply(result.responseMessage())
                .setEphemeral(true)
                .queue();
    }

    private boolean isAllowedGuild(String guildId) {
        return properties.isGuildAllowed(guildId);
    }
}
