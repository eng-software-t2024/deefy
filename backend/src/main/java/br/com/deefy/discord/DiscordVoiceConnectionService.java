package br.com.deefy.discord;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.SpeakingMode;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class DiscordVoiceConnectionService {

    private final ConcurrentMap<Long, GuildVoiceSession> sessionsByGuild = new ConcurrentHashMap<>();

    public VoiceCommandResult join(Guild guild, Member requester) {
        VoiceChannel requestedChannel = getStandardVoiceChannel(requester);
        if (requestedChannel == null) {
            return requesterHasAudioChannel(requester)
                    ? VoiceCommandResult.UNSUPPORTED_CHANNEL
                    : VoiceCommandResult.USER_NOT_IN_VOICE;
        }

        if (!guild.getSelfMember().hasPermission(
                requestedChannel,
                Permission.VIEW_CHANNEL,
                Permission.VOICE_CONNECT
        )) {
            return VoiceCommandResult.MISSING_PERMISSIONS;
        }

        long guildId = guild.getIdLong();
        GuildVoiceSession session = sessionsByGuild.computeIfAbsent(guildId, ignored -> new GuildVoiceSession());

        synchronized (session) {
            ConnectionAttempt attempt = null;
            try {
                AudioManager audioManager = guild.getAudioManager();
                AudioChannelUnion connectedChannel = audioManager.getConnectedChannel();

                if (connectedChannel != null) {
                    return connectedChannel.getIdLong() == requestedChannel.getIdLong()
                            ? VoiceCommandResult.ALREADY_IN_CHANNEL
                            : VoiceCommandResult.ALREADY_IN_ANOTHER_CHANNEL;
                }

                if (session.activeAttempt != null) {
                    return session.activeAttempt.channelId == requestedChannel.getIdLong()
                            ? VoiceCommandResult.CONNECTION_IN_PROGRESS
                            : VoiceCommandResult.CONNECTION_IN_PROGRESS_ELSEWHERE;
                }

                attempt = new ConnectionAttempt(requestedChannel.getIdLong());
                session.activeAttempt = attempt;
                installConnectionListener(audioManager, session, attempt);

                audioManager.openAudioConnection(requestedChannel);
                return VoiceCommandResult.CONNECTION_STARTED;
            } catch (RuntimeException exception) {
                clearAttempt(session, attempt);
                return VoiceCommandResult.CONNECTION_FAILED;
            }
        }
    }

    public VoiceCommandResult leave(Guild guild, Member requester) {
        VoiceChannel requesterChannel = getStandardVoiceChannel(requester);
        if (requesterChannel == null) {
            return requesterHasAudioChannel(requester)
                    ? VoiceCommandResult.UNSUPPORTED_CHANNEL
                    : VoiceCommandResult.USER_NOT_IN_VOICE;
        }

        GuildVoiceSession session = sessionsByGuild.computeIfAbsent(
                guild.getIdLong(),
                ignored -> new GuildVoiceSession()
        );

        synchronized (session) {
            AudioManager audioManager = guild.getAudioManager();
            AudioChannelUnion connectedChannel = audioManager.getConnectedChannel();

            if (connectedChannel == null) {
                return VoiceCommandResult.BOT_NOT_CONNECTED;
            }

            if (connectedChannel.getIdLong() != requesterChannel.getIdLong()) {
                return VoiceCommandResult.USER_NOT_IN_BOT_CHANNEL;
            }

            try {
                audioManager.closeAudioConnection();
                session.activeAttempt = null;
                return VoiceCommandResult.DISCONNECTION_STARTED;
            } catch (RuntimeException exception) {
                session.activeAttempt = null;
                return VoiceCommandResult.DISCONNECTION_FAILED;
            }
        }
    }

    private VoiceChannel getStandardVoiceChannel(Member requester) {
        if (requester == null) {
            return null;
        }

        GuildVoiceState voiceState = requester.getVoiceState();
        if (voiceState == null || voiceState.getChannel() == null) {
            return null;
        }

        AudioChannelUnion channel = voiceState.getChannel();
        return channel.getType() == ChannelType.VOICE ? channel.asVoiceChannel() : null;
    }

    private boolean requesterHasAudioChannel(Member requester) {
        return requester != null
                && requester.getVoiceState() != null
                && requester.getVoiceState().getChannel() != null;
    }

    private void installConnectionListener(
            AudioManager audioManager,
            GuildVoiceSession session,
            ConnectionAttempt attempt
    ) {
        ConnectionListener currentListener = audioManager.getConnectionListener();
        ConnectionListener delegate = currentListener instanceof AttemptConnectionListener managedListener
                ? managedListener.delegate
                : currentListener;
        audioManager.setConnectionListener(new AttemptConnectionListener(session, attempt, delegate));
    }

    private void clearAttempt(GuildVoiceSession session, ConnectionAttempt attempt) {
        if (attempt == null) {
            return;
        }

        synchronized (session) {
            if (session.activeAttempt == attempt) {
                session.activeAttempt = null;
            }
        }
    }

    private static final class GuildVoiceSession {
        private ConnectionAttempt activeAttempt;
    }

    private static final class ConnectionAttempt {
        private final long channelId;

        private ConnectionAttempt(long channelId) {
            this.channelId = channelId;
        }
    }

    private final class AttemptConnectionListener implements ConnectionListener {

        private final GuildVoiceSession session;
        private final ConnectionAttempt attempt;
        private final ConnectionListener delegate;

        private AttemptConnectionListener(
                GuildVoiceSession session,
                ConnectionAttempt attempt,
                ConnectionListener delegate
        ) {
            this.session = session;
            this.attempt = attempt;
            this.delegate = delegate;
        }

        @Override
        public void onPing(long ping) {
            if (delegate != null) {
                delegate.onPing(ping);
            }
        }

        @Override
        public void onStatusChange(ConnectionStatus status) {
            if (!status.name().startsWith("CONNECTING_")) {
                clearAttempt(session, attempt);
            }

            if (delegate != null) {
                delegate.onStatusChange(status);
            }
        }

        @Override
        public void onUserSpeakingModeUpdate(User user, EnumSet<SpeakingMode> modes) {
            if (delegate != null) {
                delegate.onUserSpeakingModeUpdate(user, modes);
            }
        }

        @Override
        public void onUserSpeakingModeUpdate(UserSnowflake user, EnumSet<SpeakingMode> modes) {
            if (delegate != null) {
                delegate.onUserSpeakingModeUpdate(user, modes);
            }
        }
    }

    public enum VoiceCommandResult {
        CONNECTION_STARTED("Estou entrando no seu canal de voz."),
        CONNECTION_IN_PROGRESS("Já estou entrando no seu canal de voz."),
        CONNECTION_IN_PROGRESS_ELSEWHERE("Já estou entrando em outro canal de voz desta comunidade."),
        ALREADY_IN_CHANNEL("Já estou no seu canal de voz."),
        ALREADY_IN_ANOTHER_CHANNEL("Já estou em outro canal de voz desta comunidade."),
        DISCONNECTION_STARTED("Estou saindo do canal de voz."),
        DISCONNECTION_FAILED("Não consegui iniciar a saída do canal de voz."),
        USER_NOT_IN_VOICE("Entre em um canal de voz comum para usar este comando."),
        USER_NOT_IN_BOT_CHANNEL("Você precisa estar no mesmo canal de voz que eu."),
        BOT_NOT_CONNECTED("Não estou conectado a um canal de voz desta comunidade."),
        UNSUPPORTED_CHANNEL("Nesta etapa, só posso usar canais de voz comuns."),
        MISSING_PERMISSIONS("Não tenho permissão para acessar esse canal de voz."),
        CONNECTION_FAILED("Não consegui iniciar a conexão de voz.");

        private final String responseMessage;

        VoiceCommandResult(String responseMessage) {
            this.responseMessage = responseMessage;
        }

        public String responseMessage() {
            return responseMessage;
        }
    }
}
