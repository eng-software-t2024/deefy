package br.com.deefy.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordCommandListenerTest {

    @Test
    void shouldIgnoreJoinCommandFromDirectMessage() {
        DiscordBotProperties properties = configuredProperties();
        DiscordVoiceConnectionService voiceConnectionService = mock(DiscordVoiceConnectionService.class);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.isFromGuild()).thenReturn(false);

        new DiscordCommandListener(properties, voiceConnectionService).onSlashCommandInteraction(event);

        verifyNoInteractions(voiceConnectionService);
        verify(event, never()).reply(anyString());
    }

    @Test
    void shouldReplyPongToGuildDeefyPingCommand() {
        DiscordBotProperties properties = configuredProperties();
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        Guild guild = mock(Guild.class);
        ReplyCallbackAction replyAction = mock(ReplyCallbackAction.class);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn(properties.getTestGuildId());
        when(event.getName()).thenReturn("deefy");
        when(event.getSubcommandName()).thenReturn("ping");
        when(event.reply("pong")).thenReturn(replyAction);

        DiscordVoiceConnectionService voiceConnectionService = mock(DiscordVoiceConnectionService.class);
        new DiscordCommandListener(properties, voiceConnectionService)
                .onSlashCommandInteraction(event);

        verify(event).reply("pong");
        verify(replyAction).queue();
        verifyNoInteractions(voiceConnectionService);
    }

    @Test
    void shouldIgnoreCommandsOutsideTheSupportedGuildCommand() {
        DiscordBotProperties properties = configuredProperties();
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        Guild guild = mock(Guild.class);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn("987654321098765432");
        when(event.getName()).thenReturn("deefy");
        when(event.getSubcommandName()).thenReturn("ping");

        DiscordVoiceConnectionService voiceConnectionService = mock(DiscordVoiceConnectionService.class);
        new DiscordCommandListener(properties, voiceConnectionService).onSlashCommandInteraction(event);

        verify(event, never()).reply("pong");
        verifyNoInteractions(voiceConnectionService);
    }

    @Test
    void shouldRouteJoinCommandAndReplyEphemerally() {
        DiscordBotProperties properties = configuredProperties();
        DiscordVoiceConnectionService voiceConnectionService = mock(DiscordVoiceConnectionService.class);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        Guild guild = mock(Guild.class);
        Member member = mock(Member.class);
        ReplyCallbackAction replyAction = mock(ReplyCallbackAction.class);
        String response = DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED.responseMessage();

        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(member);
        when(guild.getId()).thenReturn(properties.getTestGuildId());
        when(event.getName()).thenReturn("entrar");
        when(voiceConnectionService.join(guild, member))
                .thenReturn(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
        when(event.reply(response)).thenReturn(replyAction);
        when(replyAction.setEphemeral(true)).thenReturn(replyAction);

        new DiscordCommandListener(properties, voiceConnectionService).onSlashCommandInteraction(event);

        verify(voiceConnectionService).join(guild, member);
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
    }

    @Test
    void shouldRouteLeaveCommandAndReplyEphemerally() {
        DiscordBotProperties properties = configuredProperties();
        DiscordVoiceConnectionService voiceConnectionService = mock(DiscordVoiceConnectionService.class);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        Guild guild = mock(Guild.class);
        Member member = mock(Member.class);
        ReplyCallbackAction replyAction = mock(ReplyCallbackAction.class);
        String response = DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_STARTED.responseMessage();

        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(member);
        when(guild.getId()).thenReturn(properties.getTestGuildId());
        when(event.getName()).thenReturn("sair");
        when(voiceConnectionService.leave(guild, member))
                .thenReturn(DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_STARTED);
        when(event.reply(response)).thenReturn(replyAction);
        when(replyAction.setEphemeral(true)).thenReturn(replyAction);

        new DiscordCommandListener(properties, voiceConnectionService).onSlashCommandInteraction(event);

        verify(voiceConnectionService).leave(guild, member);
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
    }

    @Test
    void shouldReplyEphemerallyWhenLeaveFails() {
        DiscordBotProperties properties = configuredProperties();
        DiscordVoiceConnectionService voiceConnectionService = mock(DiscordVoiceConnectionService.class);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        Guild guild = mock(Guild.class);
        Member member = mock(Member.class);
        ReplyCallbackAction replyAction = mock(ReplyCallbackAction.class);
        String failureResponse = DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_FAILED
                .responseMessage();

        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(member);
        when(guild.getId()).thenReturn(properties.getTestGuildId());
        when(event.getName()).thenReturn("sair");
        when(voiceConnectionService.leave(guild, member))
                .thenReturn(DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_FAILED);
        when(event.reply(failureResponse)).thenReturn(replyAction);
        when(replyAction.setEphemeral(true)).thenReturn(replyAction);

        new DiscordCommandListener(properties, voiceConnectionService).onSlashCommandInteraction(event);

        verify(event).reply(failureResponse);
        verify(event, never()).reply(
                DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_STARTED.responseMessage()
        );
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
    }

    private DiscordBotProperties configuredProperties() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setTestGuildId("123456789012345678");
        return properties;
    }
}
