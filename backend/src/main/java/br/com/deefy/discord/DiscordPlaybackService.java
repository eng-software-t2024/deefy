package br.com.deefy.discord;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.stereotype.Service;

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

    public PlaybackCommandResult play(Guild guild, Member requester, String idOrTitle) {
        DiscordTrackCatalog.TrackLookupResult lookup = trackCatalog.find(idOrTitle);
        if (lookup.status() != DiscordTrackCatalog.TrackLookupStatus.FOUND) {
            return switch (lookup.status()) {
                case INVALID_QUERY -> result("Informe o ID ou o título da faixa.");
                case AMBIGUOUS -> result("Encontrei mais de uma faixa. Use o ID para escolher uma delas.");
                case MISSING_AUDIO_URL -> result("Essa faixa não possui um áudio disponível.");
                case UNSAFE_SOURCE -> result("A faixa não usa uma origem self-hosted autorizada.");
                default -> result("Não encontrei essa faixa no catálogo Deefy.");
            };
        }

        DiscordPlaybackSessionService.PlaybackStartResult start = sessionService.start(
                guild,
                requester,
                lookup.track()
        );
        return switch (start) {
            case STARTED -> result("Faixa preparada. A reprodução começará quando a conexão de voz estiver pronta.");
            case ALREADY_PLAYING -> result("Já existe uma faixa em reprodução nesta comunidade.");
            case USER_NOT_IN_VOICE -> result("Entre em um canal de voz comum para usar este comando.");
            case UNSUPPORTED_CHANNEL -> result("Nesta etapa, só posso usar canais de voz comuns.");
            case MISSING_PERMISSIONS -> result("Não tenho permissão para conectar e falar nesse canal.");
            case BOT_IN_ANOTHER_CHANNEL -> result("Já estou em outro canal de voz desta comunidade.");
            case VOICE_CONNECTION_IN_PROGRESS -> result("A conexão de voz ainda está em andamento. Aguarde.");
            case AUDIO_SOURCE_FAILED -> result("Não consegui preparar o áudio dessa faixa.");
            case VOICE_CONNECTION_FAILED -> result("Não consegui iniciar a conexão de voz.");
        };
    }

    public void stop(Guild guild) {
        sessionService.stop(guild);
    }

    private PlaybackCommandResult result(String responseMessage) {
        return new PlaybackCommandResult(responseMessage);
    }

    public record PlaybackCommandResult(String responseMessage) {
    }
}
