package br.com.deefy.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DiscordCommandListener extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordCommandListener.class);
    private static final String ROOT_COMMAND = "deefy";
    private static final String PING_SUBCOMMAND = "ping";
    private static final String JOIN_COMMAND = "entrar";
    private static final String LEAVE_COMMAND = "sair";
    private static final String PLAY_COMMAND = "tocar";
    private static final String PANEL_COMMAND = "painel";
    private static final String PAUSE_COMMAND = "pausar";
    private static final String RESUME_COMMAND = "retomar";
    private static final String STOP_COMMAND = "parar";
    private static final String SKIP_COMMAND = "pular";
    private static final String BACK_COMMAND = "voltar";
    private static final String RESTART_COMMAND = "reiniciar";
    private static final String QUEUE_COMMAND = "fila";
    private static final String MUSIC_OPTION = "musica";
    private static final String CONTROL_PREFIX = "deefy:playback:";

    private final DiscordBotProperties properties;
    private final DiscordPlaybackService playbackService;
    private final ConcurrentMap<Long, PlaybackPanel> panelsByGuild = new ConcurrentHashMap<>();

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
        long started = System.nanoTime();
        String correlation = DiscordDiagnosticSupport.pseudonymize(event.getId());
        String action = diagnosticSlashAction(event);
        LOGGER.info(
                "Discord interaction received: type=slash action={} correlation={} guildScope={}",
                action,
                correlation,
                event.isFromGuild() ? "guild" : "direct-message"
        );
        if (!event.isFromGuild() || !isAllowedGuild(event.getGuild() == null ? null : event.getGuild().getId())) {
            logInteractionResult("slash", action, correlation, "rejected", "guild_scope", started);
            return;
        }

        if (ROOT_COMMAND.equals(event.getName()) && PING_SUBCOMMAND.equals(event.getSubcommandName())) {
            event.reply("pong")
                    .setEphemeral(false)
                    .setAllowedMentions(List.of())
                    .queue();
            logInteractionResult("slash", action, correlation, "accepted", "ping", started);
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

        if (QUEUE_COMMAND.equals(event.getName())) {
            handleQueueCommand(event);
            return;
        }

        if (PANEL_COMMAND.equals(event.getName())) {
            bringPanelToBottom(event);
            return;
        }

        DiscordPlaybackSessionService.PlaybackControlAction controlAction = slashControl(event.getName());
        if (controlAction != null) {
            handleSlashControl(event, controlAction, correlation, started);
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        long started = System.nanoTime();
        if (!event.isFromGuild() || !isAllowedGuild(event.getGuild() == null ? null : event.getGuild().getId())) {
            logInteractionResult(
                    "button",
                    "unknown",
                    DiscordDiagnosticSupport.pseudonymize(event.getId()),
                    "rejected",
                    "guild_scope",
                    started
            );
            return;
        }
        ParsedControl control = parseControl(event.getComponentId());
        if (control == null) {
            logInteractionResult(
                    "button",
                    "unknown",
                    DiscordDiagnosticSupport.pseudonymize(event.getId()),
                    "rejected",
                    "invalid_control",
                    started
            );
            return;
        }

        String correlation = DiscordDiagnosticSupport.pseudonymize(event.getId());
        LOGGER.info(
                "Discord interaction received: type=button action={} correlation={} sessionRef={} messageRef={}",
                control.action().name().toLowerCase(Locale.ROOT),
                correlation,
                DiscordDiagnosticSupport.pseudonymize(control.sessionId()),
                DiscordDiagnosticSupport.pseudonymize(event.getMessageIdLong())
        );

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
                logInteractionResult("button", control.action().name(), correlation, "failed", "exception", started);
                sendEphemeral(hook, "Não consegui aplicar esse controle agora.");
            } else if (!result.applied()) {
                logInteractionResult(
                        "button",
                        control.action().name(),
                        correlation,
                        result.status().name().toLowerCase(Locale.ROOT),
                        "domain_result",
                        started
                );
                sendEphemeral(hook, result.responseMessage());
            } else {
                logInteractionResult("button", control.action().name(), correlation, "accepted", "applied", started);
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
        PlaybackPanel existingPanel = panelsByGuild.get(event.getGuild().getIdLong());
        if (existingPanel != null) {
            enqueueOnExistingPanel(event, existingPanel, query);
            return;
        }
        event.replyEmbeds(List.of(toEmbed(DiscordPlaybackService.initialPresentation())))
                .setEphemeral(false)
                .setAllowedMentions(List.of())
                .queue(hook -> {
                    PlaybackPanel panel = new PlaybackPanel(event.getGuild().getIdLong(), hook);
                    PlaybackPanel previous = panelsByGuild.putIfAbsent(event.getGuild().getIdLong(), panel);
                    if (previous != null) {
                        enqueueOnExistingPanel(event, previous, query);
                        return;
                    }
                    panel.start(event, query);
                });
    }

    private void enqueueOnExistingPanel(
            SlashCommandInteractionEvent event,
            PlaybackPanel panel,
            String query
    ) {
        event.deferReply(true).queue(hook -> playbackService.playAsync(
                event.getGuild(),
                event.getMember(),
                query,
                event.getId(),
                panel::acceptProgress
        ).whenComplete((result, failure) -> {
            if (failure != null) {
                hook.editOriginal("Não consegui adicionar essa faixa à fila.")
                        .setAllowedMentions(List.of())
                        .queue();
                return;
            }
            if (result.queued()) {
                panel.publishCurrent();
                hook.editOriginal("✅ Faixa adicionada à fila. O painel de reprodução foi atualizado.")
                        .setAllowedMentions(List.of())
                        .queue();
            } else if (result.sessionId() != null) {
                panel.bindSession(result.sessionId(), result.presentation())
                        .whenComplete((ignored, bindFailure) -> hook.editOriginal(
                                        bindFailure == null && Boolean.TRUE.equals(ignored)
                                                ? "✅ Nova reprodução iniciada no painel atual."
                                                : result.presentation().description()
                                )
                                .setAllowedMentions(List.of())
                                .queue());
            } else {
                hook.editOriginal(result.presentation().description())
                        .setAllowedMentions(List.of())
                        .queue();
            }
        }));
    }

    private void handleSlashControl(
            SlashCommandInteractionEvent event,
            DiscordPlaybackSessionService.PlaybackControlAction action,
            String correlation,
            long started
    ) {
        event.deferReply(true).queue(hook -> playbackService.controlAsync(
                event.getGuild(),
                event.getMember(),
                DiscordPlaybackSessionService.PlaybackControlRequest.slash(action, event.getId())
        ).whenComplete((result, failure) -> {
            if (failure != null) {
                logInteractionResult("slash", action.name(), correlation, "failed", "exception", started);
                hook.editOriginal("Não consegui aplicar esse controle agora.")
                        .setAllowedMentions(List.of())
                        .queue();
            } else if (result.applied()) {
                logInteractionResult("slash", action.name(), correlation, "accepted", "applied", started);
                hook.deleteOriginal().queue();
            } else {
                logInteractionResult(
                        "slash",
                        action.name(),
                        correlation,
                        result.status().name().toLowerCase(Locale.ROOT),
                        "domain_result",
                        started
                );
                hook.editOriginal(result.responseMessage())
                        .setAllowedMentions(List.of())
                        .queue();
            }
        }));
    }

    private void handleQueueCommand(SlashCommandInteractionEvent event) {
        DiscordPlaybackSessionService.QueueAction action = switch (
                event.getSubcommandName() == null ? "" : event.getSubcommandName()
        ) {
            case "ver" -> DiscordPlaybackSessionService.QueueAction.VIEW;
            case "remover" -> DiscordPlaybackSessionService.QueueAction.REMOVE;
            case "mover" -> DiscordPlaybackSessionService.QueueAction.MOVE;
            case "limpar" -> DiscordPlaybackSessionService.QueueAction.CLEAR;
            case "embaralhar" -> DiscordPlaybackSessionService.QueueAction.SHUFFLE;
            default -> null;
        };
        if (action == null) {
            event.reply("Subcomando de fila inválido.")
                    .setEphemeral(true)
                    .setAllowedMentions(List.of())
                    .queue();
            return;
        }
        OptionMapping originOption = event.getOption("posicao");
        OptionMapping destinationOption = event.getOption("destino");
        int origin = originOption == null ? 0 : originOption.getAsInt();
        int destination = destinationOption == null ? 0 : destinationOption.getAsInt();
        event.deferReply(false).setAllowedMentions(List.of()).queue(hook ->
                playbackService.queueAsync(
                        event.getGuild(),
                        event.getMember(),
                        new DiscordPlaybackSessionService.QueueCommandRequest(
                                action,
                                origin,
                                destination,
                                event.getId()
                        )
                ).whenComplete((result, failure) -> hook
                        .editOriginal(failure == null
                                ? queueMessage(result)
                                : "Não consegui atualizar a fila agora.")
                        .setAllowedMentions(List.of())
                        .queue(ignored -> {
                            if (failure == null) {
                                PlaybackPanel panel = panelsByGuild.get(event.getGuild().getIdLong());
                                if (panel != null) {
                                    panel.publishCurrent();
                                }
                            }
                        }))
        );
    }

    private void bringPanelToBottom(SlashCommandInteractionEvent event) {
        PlaybackPanel panel = panelsByGuild.get(event.getGuild().getIdLong());
        if (panel == null) {
            event.reply("Ainda não existe um painel ativo nesta sessão. Use /tocar primeiro.")
                    .setEphemeral(true)
                    .setAllowedMentions(List.of())
                    .queue();
            return;
        }

        event.deferReply(true)
                .setAllowedMentions(List.of())
                .queue(hook -> panel.reanchor(event).whenComplete((reanchored, failure) -> hook
                        .editOriginal(failure != null
                                ? "Não consegui reposicionar o painel agora."
                                : reanchored
                                ? "✅ Painel reposicionado no fim deste canal."
                                : "A reprodução já não está ativa nesta sessão.")
                        .setAllowedMentions(List.of())
                        .queue()));
    }

    private String queueMessage(DiscordPlaybackSessionService.QueueCommandResult result) {
        if (result.status() != DiscordPlaybackSessionService.QueueCommandStatus.APPLIED) {
            return result.responseMessage();
        }
        StringBuilder message = new StringBuilder(result.responseMessage());
        if (result.snapshot().current() != null) {
            message.append("\nTocando: ")
                    .append(safeQueueText(result.snapshot().current().track().title()));
        }
        message.append("\nPendentes:");
        if (result.snapshot().pending().isEmpty()) {
            return message.append(" nenhuma.").toString();
        }
        for (int index = 0; index < result.snapshot().pending().size(); index++) {
            DiscordPlaybackSessionService.QueueEntryView entry = result.snapshot().pending().get(index);
            message.append("\n").append(index + 1).append(". ")
                    .append(safeQueueText(entry.track().title()))
                    .append(" — ").append(safeQueueText(entry.track().artist()))
                    .append(" (por ").append(safeQueueText(entry.requester())).append(")");
        }
        return message.toString();
    }

    private String safeQueueText(String value) {
        return value == null ? "—" : value.replace("@", "@\u200B");
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
                    Button.danger(controlId("stop", sessionId), "⏹️ Parar"),
                    Button.primary(controlId("skip", sessionId), "⏭️ Pular")
                            .withDisabled(presentation.pendingCount() == 0),
                    Button.secondary(controlId("back", sessionId), "⏮️ Voltar")
                            .withDisabled(presentation.historySize() == 0),
                    Button.secondary(controlId("restart", sessionId), "🔄 Reiniciar")
            ));
            case PLAYING -> List.of(ActionRow.of(
                    Button.secondary(controlId("pause", sessionId), "⏸️ Pausar"),
                    Button.danger(controlId("stop", sessionId), "⏹️ Parar"),
                    Button.primary(controlId("skip", sessionId), "⏭️ Pular")
                            .withDisabled(presentation.pendingCount() == 0),
                    Button.secondary(controlId("back", sessionId), "⏮️ Voltar")
                            .withDisabled(presentation.historySize() == 0),
                    Button.secondary(controlId("restart", sessionId), "🔄 Reiniciar")
            ));
            case PAUSED -> List.of(ActionRow.of(
                    Button.success(controlId("resume", sessionId), "▶️ Retomar"),
                    Button.danger(controlId("stop", sessionId), "⏹️ Parar"),
                    Button.primary(controlId("skip", sessionId), "⏭️ Pular")
                            .withDisabled(presentation.pendingCount() == 0),
                    Button.secondary(controlId("back", sessionId), "⏮️ Voltar")
                            .withDisabled(presentation.historySize() == 0),
                    Button.secondary(controlId("restart", sessionId), "🔄 Reiniciar")
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
            case "skip" -> DiscordPlaybackSessionService.PlaybackControlAction.SKIP;
            case "back" -> DiscordPlaybackSessionService.PlaybackControlAction.BACK;
            case "restart" -> DiscordPlaybackSessionService.PlaybackControlAction.RESTART;
            default -> null;
        };
        return action == null ? null : new ParsedControl(action, suffix.substring(separator + 1));
    }

    private DiscordPlaybackSessionService.PlaybackControlAction slashControl(String command) {
        return switch (command) {
            case PAUSE_COMMAND -> DiscordPlaybackSessionService.PlaybackControlAction.PAUSE;
            case RESUME_COMMAND -> DiscordPlaybackSessionService.PlaybackControlAction.RESUME;
            case STOP_COMMAND -> DiscordPlaybackSessionService.PlaybackControlAction.STOP;
            case SKIP_COMMAND -> DiscordPlaybackSessionService.PlaybackControlAction.SKIP;
            case BACK_COMMAND -> DiscordPlaybackSessionService.PlaybackControlAction.BACK;
            case RESTART_COMMAND -> DiscordPlaybackSessionService.PlaybackControlAction.RESTART;
            default -> null;
        };
    }

    private String diagnosticSlashAction(SlashCommandInteractionEvent event) {
        if (ROOT_COMMAND.equals(event.getName()) && PING_SUBCOMMAND.equals(event.getSubcommandName())) {
            return "deefy.ping";
        }
        if (JOIN_COMMAND.equals(event.getName())
                || LEAVE_COMMAND.equals(event.getName())
                || PLAY_COMMAND.equals(event.getName())
                || PANEL_COMMAND.equals(event.getName())
                || PAUSE_COMMAND.equals(event.getName())
                || RESUME_COMMAND.equals(event.getName())
                || STOP_COMMAND.equals(event.getName())
                || SKIP_COMMAND.equals(event.getName())
                || BACK_COMMAND.equals(event.getName())
                || RESTART_COMMAND.equals(event.getName())
                || QUEUE_COMMAND.equals(event.getName())) {
            return event.getName();
        }
        return "unknown";
    }

    private void logInteractionResult(
            String type,
            String action,
            String correlation,
            String result,
            String reason,
            long started
    ) {
        LOGGER.info(
                "Discord interaction result: type={} action={} correlation={} result={} reason={} latencyMs={}",
                type,
                action.toLowerCase(Locale.ROOT),
                correlation,
                result,
                reason,
                (System.nanoTime() - started) / 1_000_000
        );
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
        if (presentation.pendingCount() > 0) {
            addField(embed, "Na fila", Integer.toString(presentation.pendingCount()));
            String pending = presentation.pending().stream()
                    .limit(5)
                    .map(entry -> sanitize(entry.title(), 120)
                            + " — " + sanitize(entry.artist(), 120)
                            + " (" + sanitize(entry.requester(), 80) + ")")
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("—");
            addField(embed, "Próximas faixas", pending);
            if (presentation.pendingCount() > 5) {
                addField(embed, "Mais na fila", Integer.toString(presentation.pendingCount() - 5));
            }
        }
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

    /** One authoritative, reusable playback message per guild. */
    private final class PlaybackPanel {

        private final long guildId;
        private volatile CompletableFuture<Message> panelMessage;
        private final DiscordPlaybackMessageCoordinator messages;
        private final Object gate = new Object();
        private final AtomicReference<DiscordPlaybackService.PlaybackPresentation> latestBeforeBinding =
                new AtomicReference<>();
        private final AtomicBoolean bindingInFlight = new AtomicBoolean();
        private volatile String boundSessionId;
        private volatile long messageId;

        private PlaybackPanel(long guildId, InteractionHook hook) {
            this.guildId = guildId;
            this.panelMessage = hook.retrieveOriginal().submit();
            this.messages = new DiscordPlaybackMessageCoordinator(
                    (presentation, sessionId, success, failure) -> panelMessage.whenComplete((message, retrievalFailure) -> {
                        if (retrievalFailure != null || message == null) {
                            failure.accept(retrievalFailure == null
                                    ? new IllegalStateException("Playback panel message unavailable")
                                    : retrievalFailure);
                            return;
                        }
                        MessageEditAction edit = message
                                .editMessageEmbeds(List.of(toEmbed(presentation)))
                                .setComponents(componentsFor(presentation, sessionId))
                                .setAllowedMentions(List.of());
                        edit.queue(ignored -> success.run(), failure);
                    })
            );
        }

        private CompletableFuture<Boolean> reanchor(SlashCommandInteractionEvent event) {
            String sessionId = playbackService.currentSessionId(guildId);
            if (sessionId == null) {
                return CompletableFuture.completedFuture(false);
            }

            DiscordPlaybackService.PlaybackPresentation presentation = playbackService.currentPresentation(guildId);
            MessageChannel channel = event.getChannel();
            MessageCreateAction create = channel.sendMessageEmbeds(List.of(toEmbed(presentation)))
                    .setComponents(componentsFor(presentation, sessionId))
                    .setAllowedMentions(List.of());
            return create.submit().thenCompose(newMessage ->
                    playbackService.bindControlMessageAsync(guildId, sessionId, newMessage.getIdLong())
                            .thenCompose(bound -> {
                                if (!bound) {
                                    newMessage.delete().queue();
                                    return CompletableFuture.completedFuture(false);
                                }
                                CompletableFuture<Message> previous = panelMessage;
                                panelMessage = CompletableFuture.completedFuture(newMessage);
                                messageId = newMessage.getIdLong();
                                boundSessionId = sessionId;
                                messages.publish(presentation, sessionId);
                                previous.whenComplete((oldMessage, ignored) -> {
                                    if (oldMessage != null && oldMessage.getIdLong() != newMessage.getIdLong()) {
                                        oldMessage.delete().queue();
                                    }
                                });
                                return CompletableFuture.completedFuture(true);
                            })
            );
        }

        private void start(SlashCommandInteractionEvent event, String query) {
            playbackService.playAsync(
                    event.getGuild(),
                    event.getMember(),
                    query,
                    event.getId(),
                    this::acceptProgress
            ).whenComplete((result, failure) -> {
                if (failure != null) {
                    messages.publish(unexpectedFailure(), null);
                } else if (result.sessionId() != null) {
                    bindSession(result.sessionId(), result.presentation());
                } else if (result.queued()) {
                    publishCurrent();
                } else {
                    messages.publish(result.presentation(), null);
                }
            });
        }

        private void acceptProgress(DiscordPlaybackService.PlaybackProgressUpdate progress) {
            String sessionId = progress.sessionId();
            if (sessionId == null) {
                if (boundSessionId == null) {
                    latestBeforeBinding.set(progress.presentation());
                }
                return;
            }
            if (boundSessionId != null && !playbackService.isCurrentSession(guildId, sessionId)) {
                LOGGER.info(
                        "Discord playback panel progress ignored: reason=stale_session sessionRef={}",
                        DiscordDiagnosticSupport.pseudonymize(sessionId)
                );
                return;
            }
            boolean needsBinding;
            synchronized (gate) {
                latestBeforeBinding.set(progress.presentation());
                needsBinding = !sessionId.equals(boundSessionId);
                if (!needsBinding) {
                    messages.publish(progress.presentation(), sessionId);
                }
            }
            if (needsBinding) {
                bindSession(sessionId, progress.presentation());
            }
        }

        private CompletableFuture<Boolean> bindSession(
                String sessionId,
                DiscordPlaybackService.PlaybackPresentation fallback
        ) {
            synchronized (gate) {
                if (sessionId.equals(boundSessionId)) {
                    return CompletableFuture.completedFuture(true);
                }
            }
            if (!bindingInFlight.compareAndSet(false, true)) {
                return CompletableFuture.completedFuture(true);
            }
            CompletableFuture<Long> resolvedMessage = messageId == 0
                    ? panelMessage.thenApply(message -> {
                        messageId = message.getIdLong();
                        return messageId;
                    })
                    : CompletableFuture.completedFuture(messageId);
            return resolvedMessage
                    .thenCompose(id -> playbackService.bindControlMessageAsync(guildId, sessionId, id))
                    .handle((bound, failure) -> {
                        synchronized (gate) {
                            DiscordPlaybackService.PlaybackPresentation current =
                                    latestBeforeBinding.getAndSet(null);
                            boolean accepted = failure == null && Boolean.TRUE.equals(bound);
                            if (accepted) {
                                boundSessionId = sessionId;
                                messages.publish(current == null ? fallback : current, sessionId);
                            }
                            LOGGER.info(
                                    "Discord playback panel binding: sessionRef={}, result={}",
                                    DiscordDiagnosticSupport.pseudonymize(sessionId),
                                    accepted ? "accepted" : "rejected"
                            );
                            return accepted;
                        }
                    })
                    .whenComplete((ignored, ignoredFailure) -> bindingInFlight.set(false));
        }

        private void publishCurrent() {
            String currentSession = playbackService.currentSessionId(guildId);
            if (currentSession != null) {
                messages.publish(playbackService.currentPresentation(guildId), currentSession);
            }
        }
    }

    private record ParsedControl(
            DiscordPlaybackSessionService.PlaybackControlAction action,
            String sessionId
    ) {
    }
}
