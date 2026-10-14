package br.com.deefy.discord;

import net.dv8tion.jda.api.audio.dave.DaveSessionFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DiscordDaveSessionFactoryProviderTest {

    private final DiscordDaveSessionFactoryProvider provider = new DiscordDaveSessionFactoryProvider();

    @Test
    void shouldFailClosedWhenDaveProviderIsNotOnClasspath() {
        assumeFalse(isProviderAvailable());

        assertThatThrownBy(provider::createRequired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Required DAVE provider is not available");
    }

    @Test
    void shouldLoadNativeDaveImplementationWhenSmokeIsEnabled() {
        assumeTrue(Boolean.getBoolean("deefy.discord.dave.native-smoke"));
        assumeTrue(isProviderAvailable());

        DaveSessionFactory factory = provider.createRequired();
        assertThat(factory.getClass().getName()).isEqualTo(DiscordDaveSessionFactoryProvider.JDAVE_FACTORY_CLASS);
    }

    private boolean isProviderAvailable() {
        try {
            Class.forName(DiscordDaveSessionFactoryProvider.JDAVE_FACTORY_CLASS);
            return true;
        } catch (ClassNotFoundException | LinkageError exception) {
            return false;
        }
    }

}
