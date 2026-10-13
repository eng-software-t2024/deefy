package br.com.deefy.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordCommandRegistrarTest {

    @SuppressWarnings("unchecked")
    @Test
    void shouldUpsertCommandsGloballyInNormalModeWithoutReplacingUnrelatedCommands() {
        DiscordBotProperties properties = new DiscordBotProperties();
        DiscordCommandRegistrar registrar = new DiscordCommandRegistrar(properties);
        ReadyEvent event = mock(ReadyEvent.class);
        JDA jda = mock(JDA.class);
        RestAction<Command> action = mock(RestAction.class);

        when(event.getJDA()).thenReturn(jda);
        when(jda.upsertCommand(any(CommandData.class))).thenReturn(action);

        registrar.onReady(event);

        ArgumentCaptor<CommandData> commands = ArgumentCaptor.forClass(CommandData.class);
        verify(jda, times(4)).upsertCommand(commands.capture());
        verify(jda, never()).getGuildById(any(String.class));
        assertCommandSetAndAutocomplete(commands.getAllValues());
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldRegisterOnlyInConfiguredGuildWhenTestModeIsExplicitlyEnabled() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setTestMode(true);
        properties.setTestGuildId("123456789012345678");
        DiscordCommandRegistrar registrar = new DiscordCommandRegistrar(properties);
        ReadyEvent event = mock(ReadyEvent.class);
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        RestAction<Command> action = mock(RestAction.class);

        when(event.getJDA()).thenReturn(jda);
        when(jda.getGuildById(properties.getTestGuildId())).thenReturn(guild);
        when(guild.upsertCommand(any(CommandData.class))).thenReturn(action);

        registrar.onReady(event);

        ArgumentCaptor<CommandData> commands = ArgumentCaptor.forClass(CommandData.class);
        verify(guild, times(4)).upsertCommand(commands.capture());
        verify(jda, never()).upsertCommand(any(CommandData.class));
        assertCommandSetAndAutocomplete(commands.getAllValues());
    }

    private void assertCommandSetAndAutocomplete(java.util.List<CommandData> commands) {
        assertThat(commands)
                .extracting(CommandData::getName)
                .containsExactly("deefy", "entrar", "sair", "tocar");
        SlashCommandData play = (SlashCommandData) commands.stream()
                .filter(command -> command.getName().equals("tocar"))
                .findFirst()
                .orElseThrow();
        assertThat(play.getOptions()).singleElement().satisfies(option -> {
            assertThat(option.getName()).isEqualTo("musica");
            assertThat(option.isAutoComplete()).isTrue();
        });
    }
}
