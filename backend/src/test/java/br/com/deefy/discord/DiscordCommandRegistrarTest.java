package br.com.deefy.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordCommandRegistrarTest {

    @SuppressWarnings("unchecked")
    @Test
    void shouldRegisterPingJoinAndLeaveOnlyInTheConfiguredGuild() {
        DiscordBotProperties properties = new DiscordBotProperties();
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
        verify(guild, times(3)).upsertCommand(commands.capture());
        assertThat(commands.getAllValues())
                .extracting(CommandData::getName)
                .containsExactly("deefy", "entrar", "sair");
    }
}
