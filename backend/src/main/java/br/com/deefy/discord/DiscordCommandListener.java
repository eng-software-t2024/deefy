package br.com.deefy.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
            event.reply("pong")
                    .setEphemeral(false)
                    .setAllowedMentions(List.of())
                    .queue();
            return;
        }

        if (JOIN_COMMAND.equals(event.getName())) {
            replyWithVoiceResult(event, playbackService.join(event.getGuild(), event.getMember()));
            return;
        }

        if (LEAVE_COMMAND.equals(event.getName())) {
            DiscordVoiceConnectionService.VoiceCommandResult result = voiceConnectionService.leave(
                    event.getGuild(),
                    event.getMember(),
                    () -> playbackService.stop(event.getGuild())
            );
            if (result == DiscordVoiceConnectionService.VoiceCommandResult.BOT_NOT_CONNECTED) {
                playbackService.stop(event.getGuild());
            }
            replyWithVoiceResult(event, result);
            return;
        }

        if (PLAY_COMMAND.equals(event.getName())) {
            OptionMapping option = event.getOption(MUSIC_OPTION);
            String query = option == null ? "" : option.getAsString();
            event.replyEmbeds(List.of(toEmbed(DiscordPlaybackService.initialPresentation())))
                    .setEphemeral(false)
                    .setAllowedMentions(List.of())
                    .queue(hook -> {
                        try {
                            AtomicBoolean progressPublished = new AtomicBoolean(false);
                            DiscordPlaybackService.PlaybackCommandResult result = playbackService.play(
                                    event.getGuild(),
                                    event.getMember(),
                                    query,
                                    progress -> {
                                        progressPublished.set(true);
                                        editPlaybackMessage(hook, progress.presentation());
                                    }
                            );
                            if (!progressPublished.get() || !result.awaitingProgress()) {
                                editPlaybackMessage(hook, result.presentation());
                            }
                        } catch (RuntimeException ignored) {
                            editPlaybackMessage(hook, new DiscordPlaybackService.PlaybackPresentation(
                                    DiscordPlaybackService.PlaybackMessageState.FAILED,
                                    "Falha na reprodução",
                                    "Não consegui iniciar a reprodução e a sessão foi encerrada com segurança.",
                                    null,
                                    null,
                                    null,
                                    null
                            ));
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
                .setEphemeral(false)
                .setAllowedMentions(List.of())
                .queue();
    }

    private void editPlaybackMessage(
            InteractionHook hook,
            DiscordPlaybackService.PlaybackPresentation presentation
    ) {
        hook.editOriginalEmbeds(List.of(toEmbed(presentation)))
                .setAllowedMentions(List.of())
                .queue();
    }

    private MessageEmbed toEmbed(DiscordPlaybackService.PlaybackPresentation presentation) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(colorFor(presentation.state()))
                .setAuthor("Deefy")
                .setTitle(sanitize(presentation.heading(), 256))
                .setDescription(sanitize(presentation.description(), 4_096))
                .setFooter("Deefy • Reprodução self-hosted");

        addField(embed, "Faixa", presentation.trackTitle());
        addField(embed, "Artista / créditos", presentation.artist());
        addField(embed, "Solicitado por", presentation.requester());
        addField(embed, "Canal de voz", presentation.voiceChannel());
        return embed.build();
    }

    private void addField(EmbedBuilder embed, String name, String value) {
        if (value != null && !value.isBlank()) {
            embed.addField(name, sanitize(value, 1_024), false);
        }
    }

    private int colorFor(DiscordPlaybackService.PlaybackMessageState state) {
        return switch (state) {
            case PREPARING -> 0x2DE2C5;
            case PLAYING -> 0x22C55E;
            case COMPLETED -> 0x38BDF8;
            case FAILED -> 0xEF4444;
        };
    }

    private String sanitize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        String sanitized = value
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "")
                .replace("@", "@\u200B")
                .strip();
        return sanitized.length() <= maxLength
                ? sanitized
                : sanitized.substring(0, maxLength - 1) + "…";
    }

    private boolean isAllowedGuild(String guildId) {
        return properties.isGuildAllowed(guildId);
    }
}
