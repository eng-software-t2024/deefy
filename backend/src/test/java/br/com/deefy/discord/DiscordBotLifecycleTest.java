package br.com.deefy.discord;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;

class DiscordBotLifecycleTest {

    @Test
    void shouldRemainStoppedWhenDiscordIntegrationIsDisabled() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setEnabled(false);

        DiscordBotLifecycle lifecycle = new DiscordBotLifecycle(
                properties,
                new DiscordCommandListener(properties),
                new DiscordCommandRegistrar(properties)
        );

        lifecycle.start();

        assertThat(lifecycle.isRunning()).isFalse();
        lifecycle.stop();
    }

    @Test
    void shouldRemainStoppedWhenEnabledConfigurationIsIncomplete() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setEnabled(true);

        DiscordBotLifecycle lifecycle = new DiscordBotLifecycle(
                properties,
                new DiscordCommandListener(properties),
                new DiscordCommandRegistrar(properties)
        );

        lifecycle.start();

        assertThat(lifecycle.isRunning()).isFalse();
        lifecycle.stop();
    }

    @Test
    void shouldBecomeRunningOnlyAfterReadyEvent() {
        DiscordBotProperties properties = new DiscordBotProperties();
        DiscordBotLifecycle lifecycle = new DiscordBotLifecycle(
                properties,
                new DiscordCommandListener(properties),
                new DiscordCommandRegistrar(properties)
        );

        assertThat(lifecycle.isRunning()).isFalse();

        lifecycle.onReady(mock(net.dv8tion.jda.api.events.session.ReadyEvent.class));

        assertThat(lifecycle.isRunning()).isTrue();
    }
}
