package br.com.deefy.discord;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class DiscordPlaybackService {

    private final DiscordTrackCatalog trackCatalog;
    private final DiscordPlaybackSessionService sessionService;

    public DiscordPlaybackService(
            DiscordTrackCatalog trackCatalog,
            DiscordPlaybackSessionService sessionService
    ) {
        this.trackCatalog = trackCatalog;
        this.sessionService = sessionService;
    }

    public CompletableFuture<PlaybackCommandResult> playAsync(
            Guild guild,
            Member requester,
            String titleArtistOrId,
            String interactionId,
            Consumer<PlaybackProgressUpdate> progressObserver
    ) {
        return CompletableFuture.supplyAsync(() -> trackCatalog.find(titleArtistOrId))
                .thenCompose(lookup -> startResolvedTrack(
                        guild,
                        requester,
                        lookup,
                        interactionId,
                        progressObserver
                ))
                .exceptionally(failure -> overloadedOrFailed(failure));
    }

    public PlaybackCommandResult play(Guild guild, Member requester, String titleArtistOrId) {
        return play(guild, requester, titleArtistOrId, ignored -> { });
    }

    public PlaybackCommandResult play(
            Guild guild,
            Member requester,
            String titleArtistOrId,
            Consumer<PlaybackProgressUpdate> progressObserver
    ) {
        return playAsync(guild, requester, titleArtistOrId, null, progressObserver).join();
    }

    public CompletableFuture<DiscordPlaybackSessionService.PlaybackControlResult> controlAsync(
            Guild guild,
            Member requester,
            DiscordPlaybackSessionService.PlaybackControlRequest request
    ) {
        return sessionService.controlAsync(guild, requester, request)
                .exceptionally(failure -> new DiscordPlaybackSessionService.PlaybackControlResult(
                        failureCause(failure) instanceof DiscordGuildCommandExecutor.GuildQueueSaturatedException
                                ? DiscordPlaybackSessionService.PlaybackControlStatus.FAILED
                                : DiscordPlaybackSessionService.PlaybackControlStatus.FAILED,
                        failureCause(failure) instanceof DiscordGuildCommandExecutor.GuildQueueSaturatedException
                                ? "Há muitos controles pendentes nesta comunidade. Tente novamente em instantes."
                                : "Não consegui aplicar o controle agora."
                ));
    }

    public CompletableFuture<DiscordPlaybackSessionService.QueueCommandResult> queueAsync(
            Guild guild,
            Member requester,
            DiscordPlaybackSessionService.QueueCommandRequest request
    ) {
        return sessionService.queueAsync(guild, requester, request)
                .exceptionally(failure -> new DiscordPlaybackSessionService.QueueCommandResult(
                        DiscordPlaybackSessionService.QueueCommandStatus.REJECTED,
                        "Não consegui atualizar a fila agora.",
                        DiscordPlaybackSessionService.QueueSnapshot.empty()
                ));
    }

    public CompletableFuture<Boolean> bindControlMessageAsync(
            long guildId,
            String sessionId,
            long messageId
    ) {
        return sessionService.bindControlMessageAsync(guildId, sessionId, messageId)
                .exceptionally(ignored -> false);
    }

    /** Returns the authoritative panel snapshot for the guild's active queue. */
    public PlaybackPresentation currentPresentation(long guildId) {
        DiscordPlaybackSessionService.QueueSnapshot queue = sessionService.queueSnapshot(guildId);
        DiscordPlaybackSessionService.QueueEntryView current = queue.current();
        if (current == null) {
            return initialPresentation();
        }
        DiscordGuildPlaybackSession.PlaybackState sessionState = sessionService.stateFor(guildId);
        PlaybackMessageState state = switch (sessionState) {
            case PLAYING -> PlaybackMessageState.PLAYING;
            case PAUSED -> PlaybackMessageState.PAUSED;
            case STARTING -> PlaybackMessageState.PREPARING;
            case STOPPING, IDLE -> PlaybackMessageState.STOPPED;
        };
        String heading = switch (state) {
            case PLAYING -> "Tocando agora";
            case PAUSED -> "Reprodução pausada";
            case PREPARING -> "Preparando reprodução";
            case STOPPED -> "Reprodução encerrada";
            case COMPLETED -> "Reprodução concluída";
            case FAILED -> "Falha na reprodução";
        };
        String description = switch (state) {
            case PLAYING -> "A reprodução está em andamento.";
            case PAUSED -> "A posição foi preservada. Use Retomar para continuar.";
            case PREPARING -> "A faixa está sendo preparada para tocar.";
            default -> "Permanecerei no canal aguardando outra faixa.";
        };
        return new PlaybackPresentation(
                state,
                heading,
                description,
                current.track().title(),
                current.track().artist(),
                current.requester(),
                current.voiceChannel(),
                queue.pending().size(),
                queue.historySize(),
                queue.pending().stream()
                        .map(entry -> new QueueItemPresentation(
                                entry.track().title(),
                                entry.track().artist(),
                                entry.requester()
                        ))
                        .toList()
        );
    }

    public String currentSessionId(long guildId) {
        return sessionService.currentSessionId(guildId).orElse(null);
    }

    public boolean isCurrentSession(long guildId, String sessionId) {
        return sessionId != null && sessionId.equals(currentSessionId(guildId));
    }

    public CompletableFuture<DiscordVoiceConnectionService.VoiceCommandResult> joinAsync(
            Guild guild,
            Member requester,
            String interactionId
    ) {
        return sessionService.joinIdleAsync(guild, requester, interactionId);
    }

    public DiscordVoiceConnectionService.VoiceCommandResult join(Guild guild, Member requester) {
        return sessionService.joinIdle(guild, requester);
    }

    public CompletableFuture<DiscordVoiceConnectionService.VoiceCommandResult> leaveAsync(
            Guild guild,
            Member requester,
            String interactionId
    ) {
        return sessionService.leaveAsync(guild, requester, interactionId);
    }

    public List<PlaybackSuggestion> suggest(String query) {
        return trackCatalog.suggest(query).stream()
                .map(choice -> new PlaybackSuggestion(choice.label(), choice.value()))
                .toList();
    }

    public void stop(Guild guild) {
        sessionService.stop(guild);
    }

    private CompletableFuture<PlaybackCommandResult> startResolvedTrack(
            Guild guild,
            Member requester,
            DiscordTrackCatalog.TrackLookupResult lookup,
            String interactionId,
            Consumer<PlaybackProgressUpdate> progressObserver
    ) {
        if (lookup.status() != DiscordTrackCatalog.TrackLookupStatus.FOUND) {
            return CompletableFuture.completedFuture(switch (lookup.status()) {
                case INVALID_QUERY -> result(error("Informe o título ou título - artista da faixa."));
                case AMBIGUOUS -> result(error(ambiguousMessage(lookup.choices())));
                case MISSING_AUDIO_URL -> result(error("Essa faixa não possui um áudio disponível."));
                case UNSAFE_SOURCE -> result(error("Essa faixa não está disponível para reprodução."));
                default -> result(error("Não encontrei essa faixa no catálogo Deefy."));
            });
        }

        PlaybackContext context = playbackContext(lookup.track(), requester);
        return sessionService.startAsyncWithSession(
                guild,
                requester,
                lookup.track(),
                interactionId,
                (sessionId, progress) -> progressObserver.accept(progressUpdate(progress, context, sessionId))
        ).thenApply(start -> switch (start.result()) {
            case STARTED -> pendingResult(presentation(
                    PlaybackMessageState.PREPARING,
                    "Preparando reprodução",
                    "Estou preparando a faixa e a conexão de voz.",
                    context
            ), start.sessionId());
            case QUEUED -> queuedResult(presentation(
                    PlaybackMessageState.PREPARING,
                    "Faixa adicionada à fila",
                    "Posição %d na fila pendente.".formatted(start.queuePosition()),
                    context
            ), start.queuePosition(), start.pendingCount());
            case QUEUE_FULL -> result(error("A fila desta comunidade está cheia no momento."));
            case ALREADY_PLAYING -> result(error("Já existe uma faixa em reprodução nesta comunidade."));
            case DUPLICATE_INTERACTION -> result(error("Esse pedido já foi processado."));
            case USER_NOT_IN_VOICE -> result(error("Entre em um canal de voz comum para usar este comando."));
            case UNSUPPORTED_CHANNEL -> result(error("Nesta etapa, só posso usar canais de voz comuns."));
            case MISSING_PERMISSIONS -> result(error("Não tenho permissão para conectar e falar nesse canal."));
            case BOT_IN_ANOTHER_CHANNEL -> result(error("Já estou em outro canal de voz desta comunidade."));
            case VOICE_CONNECTION_IN_PROGRESS -> result(error("A conexão de voz ainda está em andamento. Aguarde."));
            case AUDIO_SOURCE_FAILED -> result(failure(context));
            case VOICE_CONNECTION_FAILED -> result(error("Não consegui iniciar a conexão de voz."));
        });
    }

    private PlaybackCommandResult overloadedOrFailed(Throwable failure) {
        Throwable cause = failureCause(failure);
        return result(error(cause instanceof DiscordGuildCommandExecutor.GuildQueueSaturatedException
                ? "Há muitos pedidos pendentes nesta comunidade. Tente novamente em instantes."
                : "Não consegui iniciar a reprodução agora."));
    }

    private Throwable failureCause(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private PlaybackCommandResult result(PlaybackPresentation presentation) {
        return new PlaybackCommandResult(presentation, false, null, false, 0, 0);
    }

    private PlaybackCommandResult pendingResult(PlaybackPresentation presentation, String sessionId) {
        return new PlaybackCommandResult(presentation, true, sessionId, false, 0, 0);
    }

    private PlaybackCommandResult queuedResult(
            PlaybackPresentation presentation,
            int queuePosition,
            int pendingCount
    ) {
        return new PlaybackCommandResult(presentation, false, null, true, queuePosition, pendingCount);
    }

    private PlaybackProgressUpdate progressUpdate(
            DiscordPlaybackSessionService.PlaybackProgress progress,
            PlaybackContext context,
            String sessionId
    ) {
        PlaybackPresentation presentation = switch (progress) {
            case VOICE_CONNECTED -> presentation(
                    PlaybackMessageState.PREPARING,
                    "Conexão pronta",
                    "A faixa está sendo preparada para tocar.",
                    context
            );
            case AUDIO_FRAMES_AVAILABLE, RESUMED -> presentation(
                    PlaybackMessageState.PLAYING,
                    "Tocando agora",
                    "A reprodução está em andamento.",
                    context
            );
            case PAUSED -> presentation(
                    PlaybackMessageState.PAUSED,
                    "Reprodução pausada",
                    "A posição foi preservada. Use Retomar para continuar.",
                    context
            );
            case STOPPED -> presentation(
                    PlaybackMessageState.STOPPED,
                    "Reprodução encerrada",
                    "Permanecerei no canal por até 10 minutos aguardando outra faixa.",
                    context
            );
            case PAUSE_TIMEOUT -> presentation(
                    PlaybackMessageState.FAILED,
                    "Pausa expirada",
                    "A reprodução ficou pausada por 10 minutos e a sessão foi encerrada.",
                    context
            );
            case START_TIMEOUT -> presentation(
                    PlaybackMessageState.FAILED,
                    "Não foi possível reproduzir",
                    "A reprodução não começou dentro do tempo esperado.",
                    context
            );
            case FAILED -> failure(context);
            case COMPLETED -> presentation(
                    PlaybackMessageState.COMPLETED,
                    "Reprodução concluída",
                    "Permanecerei no canal por até 10 minutos aguardando outra faixa.",
                    context
            );
        };
        return new PlaybackProgressUpdate(presentation, sessionId);
    }

    private String ambiguousMessage(List<DiscordTrackCatalog.TrackChoice> choices) {
        String options = choices.stream()
                .map(DiscordTrackCatalog.TrackChoice::label)
                .collect(Collectors.joining("; "));
        return "Encontrei mais de uma faixa: %s. Refine usando título - artista.".formatted(options);
    }

    private PlaybackContext playbackContext(DiscordTrack track, Member requester) {
        String requesterName = requester == null ? "Usuário" : requester.getEffectiveName();
        GuildVoiceState voiceState = requester == null ? null : requester.getVoiceState();
        AudioChannelUnion channel = voiceState == null ? null : voiceState.getChannel();
        String voiceChannelName = channel == null ? null : channel.getName();
        return new PlaybackContext(requester == null || requester.getGuild() == null
                ? 0L
                : requester.getGuild().getIdLong(), track, requesterName, voiceChannelName);
    }

    private PlaybackPresentation failure(PlaybackContext context) {
        return presentation(
                PlaybackMessageState.FAILED,
                "Falha na reprodução",
                "Não consegui reproduzir essa faixa. A sessão foi encerrada com segurança.",
                context
        );
    }

    private PlaybackPresentation error(String description) {
        return new PlaybackPresentation(
                PlaybackMessageState.FAILED,
                "Não foi possível atender ao pedido",
                description,
                null,
                null,
                null,
                null
        );
    }

    private PlaybackPresentation presentation(
            PlaybackMessageState state,
            String heading,
            String description,
            PlaybackContext context
    ) {
        DiscordPlaybackSessionService.QueueSnapshot queue = context.guildId() == 0L
                ? DiscordPlaybackSessionService.QueueSnapshot.empty()
                : sessionService.queueSnapshot(context.guildId());
        return new PlaybackPresentation(
                state,
                heading,
                description,
                context.track().title(),
                context.track().artist(),
                context.requesterName(),
                context.voiceChannelName(),
                queue.pending().size(),
                queue.historySize(),
                queue.pending().stream()
                        .map(entry -> new QueueItemPresentation(
                                entry.track().title(),
                                entry.track().artist(),
                                entry.requester()
                        ))
                        .toList()
        );
    }

    public static PlaybackPresentation initialPresentation() {
        return new PlaybackPresentation(
                PlaybackMessageState.PREPARING,
                "Preparando reprodução",
                "Validando a faixa e o canal de voz.",
                null,
                null,
                null,
                null,
                0,
                0
        );
    }

    private record PlaybackContext(
            long guildId,
            DiscordTrack track,
            String requesterName,
            String voiceChannelName
    ) {
    }

    public record PlaybackCommandResult(
            PlaybackPresentation presentation,
            boolean awaitingProgress,
            String sessionId,
            boolean queued,
            int queuePosition,
            int pendingCount
    ) {

        public PlaybackCommandResult(PlaybackPresentation presentation) {
            this(presentation, false, null, false, 0, 0);
        }

        public PlaybackCommandResult(PlaybackPresentation presentation, boolean awaitingProgress) {
            this(presentation, awaitingProgress, null, false, 0, 0);
        }

        public PlaybackCommandResult(
                PlaybackPresentation presentation,
                boolean awaitingProgress,
                String sessionId
        ) {
            this(presentation, awaitingProgress, sessionId, false, 0, 0);
        }
    }

    public record PlaybackProgressUpdate(PlaybackPresentation presentation, String sessionId) {

        public PlaybackProgressUpdate(PlaybackPresentation presentation) {
            this(presentation, null);
        }
    }

    public record PlaybackSuggestion(String label, String value) {
    }

    public record PlaybackPresentation(
            PlaybackMessageState state,
            String heading,
            String description,
            String trackTitle,
            String artist,
            String requester,
            String voiceChannel,
            int pendingCount,
            int historySize,
            List<QueueItemPresentation> pending
    ) {

        public PlaybackPresentation(
                PlaybackMessageState state,
                String heading,
                String description,
                String trackTitle,
                String artist,
                String requester,
                String voiceChannel,
                int pendingCount,
                int historySize
        ) {
            this(state, heading, description, trackTitle, artist, requester, voiceChannel,
                    pendingCount, historySize, List.of());
        }

        public PlaybackPresentation(
                PlaybackMessageState state,
                String heading,
                String description,
                String trackTitle,
                String artist,
                String requester,
                String voiceChannel
        ) {
            this(state, heading, description, trackTitle, artist, requester, voiceChannel, 0, 0, List.of());
        }
    }

    public record QueueItemPresentation(String title, String artist, String requester) {
    }

    public enum PlaybackMessageState {
        PREPARING,
        PLAYING,
        PAUSED,
        COMPLETED,
        STOPPED,
        FAILED
    }
}
