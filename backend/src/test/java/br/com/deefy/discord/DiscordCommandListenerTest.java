package br.com.deefy.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.interactions.AutoCompleteQuery;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.interactions.AutoCompleteCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
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
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.isFromGuild()).thenReturn(false);

        new DiscordCommandListener(properties, voiceConnectionService, playbackService)
                .onSlashCommandInteraction(event);

        verifyNoInteractions(voiceConnectionService);
        verifyNoInteractions(playbackService);
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
        new DiscordCommandListener(properties, voiceConnectionService, mock(DiscordPlaybackService.class))
                .onSlashCommandInteraction(event);

        verify(event).reply("pong");
        verify(replyAction).queue();
        verifyNoInteractions(voiceConnectionService);
    }

    @Test
    void shouldAcceptCommandsFromAnyGuildInNormalModeWithoutConfiguredGuild() {
        DiscordBotProperties properties = new DiscordBotProperties();
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        Guild guild = mock(Guild.class);
        ReplyCallbackAction replyAction = mock(ReplyCallbackAction.class);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn("987654321098765432");
        when(event.getName()).thenReturn("deefy");
        when(event.getSubcommandName()).thenReturn("ping");
        when(event.reply("pong")).thenReturn(replyAction);

        new DiscordCommandListener(
                properties,
                mock(DiscordVoiceConnectionService.class),
                mock(DiscordPlaybackService.class)
        ).onSlashCommandInteraction(event);

        verify(event).reply("pong");
        verify(replyAction).queue();
    }

    @Test
    void shouldIgnoreCommandsOutsideTheConfiguredGuildOnlyInTestMode() {
        DiscordBotProperties properties = configuredProperties();
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        Guild guild = mock(Guild.class);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn("987654321098765432");
        when(event.getName()).thenReturn("deefy");
        when(event.getSubcommandName()).thenReturn("ping");

        DiscordVoiceConnectionService voiceConnectionService = mock(DiscordVoiceConnectionService.class);
        new DiscordCommandListener(properties, voiceConnectionService, mock(DiscordPlaybackService.class))
                .onSlashCommandInteraction(event);

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

        new DiscordCommandListener(properties, voiceConnectionService, mock(DiscordPlaybackService.class))
                .onSlashCommandInteraction(event);

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

        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        new DiscordCommandListener(properties, voiceConnectionService, playbackService)
                .onSlashCommandInteraction(event);

        verify(voiceConnectionService).leave(guild, member);
        verify(playbackService).stop(guild);
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

        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        new DiscordCommandListener(properties, voiceConnectionService, playbackService)
                .onSlashCommandInteraction(event);

        verify(event).reply(failureResponse);
        verify(event, never()).reply(
                DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_STARTED.responseMessage()
        );
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
        verify(playbackService, never()).stop(guild);
    }

    @Test
    void shouldRoutePlayCommandWithRequiredMusicOption() {
        DiscordBotProperties properties = configuredProperties();
        DiscordVoiceConnectionService voiceConnectionService = mock(DiscordVoiceConnectionService.class);
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        Guild guild = mock(Guild.class);
        Member member = mock(Member.class);
        OptionMapping option = mock(OptionMapping.class);
        ReplyCallbackAction replyAction = mock(ReplyCallbackAction.class);
        String response = "Faixa preparada para reprodução.";

        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(member);
        when(guild.getId()).thenReturn(properties.getTestGuildId());
        when(event.getName()).thenReturn("tocar");
        when(event.getOption("musica")).thenReturn(option);
        when(option.getAsString()).thenReturn("13");
        when(playbackService.play(guild, member, "13"))
                .thenReturn(new DiscordPlaybackService.PlaybackCommandResult(response));
        when(event.reply(response)).thenReturn(replyAction);
        when(replyAction.setEphemeral(true)).thenReturn(replyAction);

        new DiscordCommandListener(properties, voiceConnectionService, playbackService)
                .onSlashCommandInteraction(event);

        verify(playbackService).play(guild, member, "13");
        verify(replyAction).setEphemeral(true);
        verify(replyAction).queue();
    }

    @Test
    void shouldReturnSelfHostedCatalogSuggestionsForPlayAutocomplete() {
        DiscordBotProperties properties = new DiscordBotProperties();
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        CommandAutoCompleteInteractionEvent event = mock(CommandAutoCompleteInteractionEvent.class);
        Guild guild = mock(Guild.class);
        AutoCompleteQuery focusedOption = mock(AutoCompleteQuery.class);
        AutoCompleteCallbackAction action = mock(AutoCompleteCallbackAction.class);

        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn("987654321098765432");
        when(event.getName()).thenReturn("tocar");
        when(event.getFocusedOption()).thenReturn(focusedOption);
        when(focusedOption.getName()).thenReturn("musica");
        when(focusedOption.getValue()).thenReturn("xtranho");
        when(playbackService.suggest("xtranho")).thenReturn(List.of(
                new DiscordPlaybackService.PlaybackSuggestion("Matuê, BRANDÃO85 - XTRANHO", "369")
        ));
        when(event.replyChoices(anyList())).thenReturn(action);

        new DiscordCommandListener(
                properties,
                mock(DiscordVoiceConnectionService.class),
                playbackService
        ).onCommandAutoCompleteInteraction(event);

        verify(playbackService).suggest("xtranho");
        verify(event).replyChoices(anyList());
        verify(action).queue();
    }

    private DiscordBotProperties configuredProperties() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setTestMode(true);
        properties.setTestGuildId("123456789012345678");
        return properties;
    }
}
