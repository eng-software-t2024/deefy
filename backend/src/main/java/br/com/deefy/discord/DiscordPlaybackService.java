package br.com.deefy.discord;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import org.springframework.stereotype.Service;

import java.util.List;
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

    public PlaybackCommandResult play(Guild guild, Member requester, String titleArtistOrId) {
        return play(guild, requester, titleArtistOrId, ignored -> { });
    }

    public PlaybackCommandResult play(
            Guild guild,
            Member requester,
            String titleArtistOrId,
            Consumer<PlaybackProgressUpdate> progressObserver
    ) {
        DiscordTrackCatalog.TrackLookupResult lookup = trackCatalog.find(titleArtistOrId);
        if (lookup.status() != DiscordTrackCatalog.TrackLookupStatus.FOUND) {
            return switch (lookup.status()) {
                case INVALID_QUERY -> result(error("Informe o título ou título - artista da faixa."));
                case AMBIGUOUS -> result(error(ambiguousMessage(lookup.choices())));
                case MISSING_AUDIO_URL -> result(error("Essa faixa não possui um áudio disponível."));
                case UNSAFE_SOURCE -> result(error("Essa faixa não está disponível para reprodução."));
                default -> result(error("Não encontrei essa faixa no catálogo Deefy."));
            };
        }

        PlaybackContext context = playbackContext(lookup.track(), requester);
        DiscordPlaybackSessionService.PlaybackStartResult start = sessionService.start(
                guild,
                requester,
                lookup.track(),
                progress -> progressObserver.accept(progressUpdate(progress, context))
        );
        return switch (start) {
            case STARTED -> pendingResult(presentation(
                    PlaybackMessageState.PREPARING,
                    "Preparando reprodução",
                    "Estou preparando a faixa e a conexão de voz.",
                    context
            ));
            case ALREADY_PLAYING -> result(error("Já existe uma faixa em reprodução nesta comunidade."));
            case USER_NOT_IN_VOICE -> result(error("Entre em um canal de voz comum para usar este comando."));
            case UNSUPPORTED_CHANNEL -> result(error("Nesta etapa, só posso usar canais de voz comuns."));
            case MISSING_PERMISSIONS -> result(error("Não tenho permissão para conectar e falar nesse canal."));
            case BOT_IN_ANOTHER_CHANNEL -> result(error("Já estou em outro canal de voz desta comunidade."));
            case VOICE_CONNECTION_IN_PROGRESS -> result(error("A conexão de voz ainda está em andamento. Aguarde."));
            case AUDIO_SOURCE_FAILED -> result(failure(context));
            case VOICE_CONNECTION_FAILED -> result(error("Não consegui iniciar a conexão de voz."));
        };
    }

    public List<PlaybackSuggestion> suggest(String query) {
        return trackCatalog.suggest(query).stream()
                .map(choice -> new PlaybackSuggestion(choice.label(), choice.value()))
                .toList();
    }

    public void stop(Guild guild) {
        sessionService.stop(guild);
    }

    public DiscordVoiceConnectionService.VoiceCommandResult join(Guild guild, Member requester) {
        return sessionService.joinIdle(guild, requester);
    }

    private PlaybackCommandResult result(PlaybackPresentation presentation) {
        return new PlaybackCommandResult(presentation, false);
    }

    private PlaybackCommandResult pendingResult(PlaybackPresentation presentation) {
        return new PlaybackCommandResult(presentation, true);
    }

    private PlaybackProgressUpdate progressUpdate(
            DiscordPlaybackSessionService.PlaybackProgress progress,
            PlaybackContext context
    ) {
        PlaybackPresentation presentation = switch (progress) {
            case VOICE_CONNECTED -> presentation(
                    PlaybackMessageState.PREPARING,
                    "Conexão pronta",
                    "A faixa está sendo preparada para tocar.",
                    context
            );
            case AUDIO_FRAMES_AVAILABLE -> presentation(
                    PlaybackMessageState.PLAYING,
                    "Tocando agora",
                    "A reprodução começou.",
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
        return new PlaybackProgressUpdate(presentation);
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
        return new PlaybackContext(track, requesterName, voiceChannelName);
    }

    private PlaybackPresentation failure(PlaybackContext context) {
        return presentation(
                PlaybackMessageState.FAILED,
                "Falha na reprodução",
                "Não consegui reproduzir essa faixa. A sessão de áudio foi encerrada com segurança.",
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
        return new PlaybackPresentation(
                state,
                heading,
                description,
                context.track().title(),
                context.track().artist(),
                context.requesterName(),
                context.voiceChannelName()
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
                null
        );
    }

    private record PlaybackContext(
            DiscordTrack track,
            String requesterName,
            String voiceChannelName
    ) {
    }

    public record PlaybackCommandResult(PlaybackPresentation presentation, boolean awaitingProgress) {

        public PlaybackCommandResult(PlaybackPresentation presentation) {
            this(presentation, false);
        }
    }

    public record PlaybackProgressUpdate(PlaybackPresentation presentation) {
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
            String voiceChannel
    ) {
    }

    public enum PlaybackMessageState {
        PREPARING,
        PLAYING,
        COMPLETED,
        FAILED
    }
}
