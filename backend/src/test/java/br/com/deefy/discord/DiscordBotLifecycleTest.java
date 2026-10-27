package br.com.deefy.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class DiscordBotLifecycleTest {

    @Test
    void shouldRemainStoppedWhenDiscordIntegrationIsDisabled() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setEnabled(false);

        DiscordBotLifecycle lifecycle = new DiscordBotLifecycle(
                properties,
                new DiscordCommandListener(
                        properties,
                        mock(DiscordVoiceConnectionService.class),
                        mock(DiscordPlaybackService.class)
                ),
                new DiscordCommandRegistrar(properties),
                mock(DiscordPlaybackSessionService.class),
                mock(DiscordOpusNativeProvider.class),
                mock(DiscordDaveSessionFactoryProvider.class)
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
                new DiscordCommandListener(
                        properties,
                        mock(DiscordVoiceConnectionService.class),
                        mock(DiscordPlaybackService.class)
                ),
                new DiscordCommandRegistrar(properties),
                mock(DiscordPlaybackSessionService.class),
                mock(DiscordOpusNativeProvider.class),
                mock(DiscordDaveSessionFactoryProvider.class)
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
                new DiscordCommandListener(
                        properties,
                        mock(DiscordVoiceConnectionService.class),
                        mock(DiscordPlaybackService.class)
                ),
                new DiscordCommandRegistrar(properties),
                mock(DiscordPlaybackSessionService.class),
                mock(DiscordOpusNativeProvider.class),
                mock(DiscordDaveSessionFactoryProvider.class)
        );

        assertThat(lifecycle.isRunning()).isFalse();

        lifecycle.onReady(mock(net.dv8tion.jda.api.events.session.ReadyEvent.class));

        assertThat(lifecycle.isRunning()).isTrue();
    }

    @Test
    void shouldInitializeOpusBeforeCreatingDaveSessionFactory() {
        DiscordBotProperties properties = new DiscordBotProperties();
        DiscordOpusNativeProvider opusProvider = mock(DiscordOpusNativeProvider.class);
        DiscordDaveSessionFactoryProvider daveProvider = mock(DiscordDaveSessionFactoryProvider.class);
        DiscordBotLifecycle lifecycle = new DiscordBotLifecycle(
                properties,
                new DiscordCommandListener(
                        properties,
                        mock(DiscordVoiceConnectionService.class),
                        mock(DiscordPlaybackService.class)
                ),
                new DiscordCommandRegistrar(properties),
                mock(DiscordPlaybackSessionService.class),
                opusProvider,
                daveProvider
        );

        lifecycle.initializeRequiredAudioNatives();

        InOrder order = inOrder(opusProvider, daveProvider);
        order.verify(opusProvider).loadRequired();
        order.verify(daveProvider).createRequired();
    }

    @Test
    void shouldConfigureAndClearPresenceWithTheJdaLifecycle() {
        DiscordBotProperties properties = new DiscordBotProperties();
        DiscordPresenceService presence = mock(DiscordPresenceService.class);
        ReadyEvent readyEvent = mock(ReadyEvent.class);
        JDA jda = mock(JDA.class);
        when(readyEvent.getJDA()).thenReturn(jda);
        DiscordBotLifecycle lifecycle = new DiscordBotLifecycle(
                properties,
                new DiscordCommandListener(
                        properties,
                        mock(DiscordVoiceConnectionService.class),
                        mock(DiscordPlaybackService.class)
                ),
                new DiscordCommandRegistrar(properties),
                mock(DiscordPlaybackSessionService.class),
                presence,
                mock(DiscordOpusNativeProvider.class),
                mock(DiscordDaveSessionFactoryProvider.class),
                null
        );

        lifecycle.onReady(readyEvent);
        lifecycle.stop();

        verify(presence).configure(jda);
        verify(presence).clearAll();
    }
}
