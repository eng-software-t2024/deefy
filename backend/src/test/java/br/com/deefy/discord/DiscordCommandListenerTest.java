package br.com.deefy.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordCommandListenerTest {

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

        new DiscordCommandListener(properties).onSlashCommandInteraction(event);

        verify(event).reply("pong");
        verify(replyAction).queue();
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

        new DiscordCommandListener(properties).onSlashCommandInteraction(event);

        verify(event, never()).reply("pong");
    }

    private DiscordBotProperties configuredProperties() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setTestGuildId("123456789012345678");
        return properties;
    }
}
