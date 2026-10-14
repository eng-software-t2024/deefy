package br.com.deefy.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DiscordCommandListener extends ListenerAdapter {

    private static final String ROOT_COMMAND = "deefy";
    private static final String PING_SUBCOMMAND = "ping";
    private static final String JOIN_COMMAND = "entrar";
    private static final String LEAVE_COMMAND = "sair";
    private static final String PLAY_COMMAND = "tocar";
    private static final String PAUSE_COMMAND = "pausar";
    private static final String RESUME_COMMAND = "retomar";
    private static final String STOP_COMMAND = "parar";
    private static final String MUSIC_OPTION = "musica";
    private static final String CONTROL_PREFIX = "deefy:playback:";

    private final DiscordBotProperties properties;
    private final DiscordPlaybackService playbackService;

    public DiscordCommandListener(
            DiscordBotProperties properties,
            DiscordVoiceConnectionService voiceConnectionService,
            DiscordPlaybackService playbackService
    ) {
        this.properties = properties;
        this.playbackService = playbackService;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() || !isAllowedGuild(event.getGuild() == null ? null : event.getGuild().getId())) {
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
            deferPublicVoiceReply(
                    event,
                    playbackService.joinAsync(event.getGuild(), event.getMember(), event.getId())
            );
            return;
        }

        if (LEAVE_COMMAND.equals(event.getName())) {
            deferPublicVoiceReply(
                    event,
                    playbackService.leaveAsync(event.getGuild(), event.getMember(), event.getId())
            );
            return;
        }

        if (PLAY_COMMAND.equals(event.getName())) {
            startPlayback(event);
            return;
        }

        DiscordPlaybackSessionService.PlaybackControlAction action = slashControl(event.getName());
        if (action != null) {
            handleSlashControl(event, action);
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.isFromGuild() || !isAllowedGuild(event.getGuild() == null ? null : event.getGuild().getId())) {
            return;
        }
        ParsedControl control = parseControl(event.getComponentId());
        if (control == null) {
            return;
        }

        event.deferEdit().queue(hook -> playbackService.controlAsync(
                event.getGuild(),
                event.getMember(),
                DiscordPlaybackSessionService.PlaybackControlRequest.button(
                        control.action(),
                        control.sessionId(),
                        event.getMessageIdLong(),
                        event.getId()
                )
        ).whenComplete((result, failure) -> {
            if (failure != null) {
                sendEphemeral(hook, "Não consegui aplicar esse controle agora.");
            } else if (!result.applied()) {
                sendEphemeral(hook, result.responseMessage());
            }
        }));
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

    private void startPlayback(SlashCommandInteractionEvent event) {
        OptionMapping option = event.getOption(MUSIC_OPTION);
        String query = option == null ? "" : option.getAsString();
        event.replyEmbeds(List.of(toEmbed(DiscordPlaybackService.initialPresentation())))
                .setEphemeral(false)
                .setAllowedMentions(List.of())
                .queue(hook -> {
                    DiscordPlaybackMessageCoordinator messages = new DiscordPlaybackMessageCoordinator(
                            (presentation, sessionId, success, failure) -> hook
                                    .editOriginalEmbeds(List.of(toEmbed(presentation)))
                                    .setComponents(componentsFor(presentation, sessionId))
                                    .setAllowedMentions(List.of())
                                    .queue(ignored -> success.run(), failure)
                    );
                    AtomicReference<String> sessionId = new AtomicReference<>();
                    AtomicReference<DiscordPlaybackService.PlaybackPresentation> bufferedProgress =
                            new AtomicReference<>();
                    AtomicBoolean controlsReady = new AtomicBoolean(false);
                    Object publicationGate = new Object();

                    playbackService.playAsync(
                            event.getGuild(),
                            event.getMember(),
                            query,
                            event.getId(),
                            progress -> {
                                synchronized (publicationGate) {
                                    String activeSession = sessionId.get();
                                    if (activeSession == null || !controlsReady.get()) {
                                        bufferedProgress.set(progress.presentation());
                                    } else {
                                        messages.publish(progress.presentation(), activeSession);
                                    }
                                }
                            }
                    ).whenComplete((result, failure) -> {
                        if (failure != null) {
                            messages.publish(unexpectedFailure(), null);
                            return;
                        }
                        sessionId.set(result.sessionId());
                        if (result.sessionId() == null) {
                            messages.publish(result.presentation(), null);
                            return;
                        }
                        bindControlMessage(event, hook, result.sessionId()).whenComplete((bound, bindFailure) -> {
                            synchronized (publicationGate) {
                                DiscordPlaybackService.PlaybackPresentation buffered = bufferedProgress.getAndSet(null);
                                DiscordPlaybackService.PlaybackPresentation current = buffered != null
                                        ? buffered
                                        : result.presentation();
                                boolean controlsBound = bindFailure == null && Boolean.TRUE.equals(bound);
                                messages.publish(current, controlsBound ? result.sessionId() : null);
                                controlsReady.set(controlsBound);
                            }
                        });
                    });
                });
    }

    private CompletableFuture<Boolean> bindControlMessage(
            SlashCommandInteractionEvent event,
            InteractionHook hook,
            String sessionId
    ) {
        return hook.retrieveOriginal().submit()
                .thenCompose(message -> playbackService.bindControlMessageAsync(
                        event.getGuild().getIdLong(),
                        sessionId,
                        message.getIdLong()
                ))
                .exceptionally(ignored -> false);
    }

    private void handleSlashControl(
            SlashCommandInteractionEvent event,
            DiscordPlaybackSessionService.PlaybackControlAction action
    ) {
        event.deferReply(true).queue(hook -> playbackService.controlAsync(
                event.getGuild(),
                event.getMember(),
                DiscordPlaybackSessionService.PlaybackControlRequest.slash(action, event.getId())
        ).whenComplete((result, failure) -> {
            if (failure != null) {
                hook.editOriginal("Não consegui aplicar esse controle agora.")
                        .setAllowedMentions(List.of())
                        .queue();
            } else if (result.applied()) {
                hook.deleteOriginal().queue();
            } else {
                hook.editOriginal(result.responseMessage())
                        .setAllowedMentions(List.of())
                        .queue();
            }
        }));
    }

    private void deferPublicVoiceReply(
            SlashCommandInteractionEvent event,
            CompletableFuture<DiscordVoiceConnectionService.VoiceCommandResult> future
    ) {
        event.deferReply(false)
                .setAllowedMentions(List.of())
                .queue(hook -> future.whenComplete((result, failure) -> hook
                        .editOriginal(failure == null
                                ? result.responseMessage()
                                : "Não consegui concluir o comando agora.")
                        .setAllowedMentions(List.of())
                        .queue()));
    }

    private void sendEphemeral(InteractionHook hook, String message) {
        hook.sendMessage(message)
                .setEphemeral(true)
                .setAllowedMentions(List.of())
                .queue();
    }

    private List<ActionRow> componentsFor(
            DiscordPlaybackService.PlaybackPresentation presentation,
            String sessionId
    ) {
        if (sessionId == null) {
            return List.of();
        }
        return switch (presentation.state()) {
            case PREPARING -> List.of(ActionRow.of(
                    Button.secondary(controlId("pause", sessionId), "⏸️ Pausar").asDisabled(),
                    Button.danger(controlId("stop", sessionId), "⏹️ Parar")
            ));
            case PLAYING -> List.of(ActionRow.of(
                    Button.secondary(controlId("pause", sessionId), "⏸️ Pausar"),
                    Button.danger(controlId("stop", sessionId), "⏹️ Parar")
            ));
            case PAUSED -> List.of(ActionRow.of(
                    Button.success(controlId("resume", sessionId), "▶️ Retomar"),
                    Button.danger(controlId("stop", sessionId), "⏹️ Parar")
            ));
            case COMPLETED, STOPPED, FAILED -> List.of();
        };
    }

    private String controlId(String action, String sessionId) {
        return CONTROL_PREFIX + action + ":" + sessionId;
    }

    private ParsedControl parseControl(String componentId) {
        if (componentId == null || !componentId.startsWith(CONTROL_PREFIX)) {
            return null;
        }
        String suffix = componentId.substring(CONTROL_PREFIX.length());
        int separator = suffix.indexOf(':');
        if (separator <= 0 || separator == suffix.length() - 1) {
            return null;
        }
        DiscordPlaybackSessionService.PlaybackControlAction action = switch (
                suffix.substring(0, separator).toLowerCase(Locale.ROOT)
        ) {
            case "pause" -> DiscordPlaybackSessionService.PlaybackControlAction.PAUSE;
            case "resume" -> DiscordPlaybackSessionService.PlaybackControlAction.RESUME;
            case "stop" -> DiscordPlaybackSessionService.PlaybackControlAction.STOP;
            default -> null;
        };
        return action == null ? null : new ParsedControl(action, suffix.substring(separator + 1));
    }

    private DiscordPlaybackSessionService.PlaybackControlAction slashControl(String command) {
        return switch (command) {
            case PAUSE_COMMAND -> DiscordPlaybackSessionService.PlaybackControlAction.PAUSE;
            case RESUME_COMMAND -> DiscordPlaybackSessionService.PlaybackControlAction.RESUME;
            case STOP_COMMAND -> DiscordPlaybackSessionService.PlaybackControlAction.STOP;
            default -> null;
        };
    }

    private DiscordPlaybackService.PlaybackPresentation unexpectedFailure() {
        return new DiscordPlaybackService.PlaybackPresentation(
                DiscordPlaybackService.PlaybackMessageState.FAILED,
                "Falha na reprodução",
                "Não consegui iniciar a reprodução e a sessão foi encerrada com segurança.",
                null,
                null,
                null,
                null
        );
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
            case PAUSED -> 0xF59E0B;
            case COMPLETED -> 0x38BDF8;
            case STOPPED -> 0x94A3B8;
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

    private record ParsedControl(
            DiscordPlaybackSessionService.PlaybackControlAction action,
            String sessionId
    ) {
    }
}
