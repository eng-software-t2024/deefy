package br.com.deefy.discord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordBotPropertiesTest {

    @Test
    void shouldHaveNoValidationErrorsWhenEnabledConfigurationIsComplete() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setEnabled(true);
        properties.setToken("token-is-only-a-test-value");
        properties.setTestGuildId("123456789012345678");

        assertThat(properties.validationErrors()).isEmpty();
    }

    @Test
    void shouldReportMissingTokenAndGuildWithoutExposingToken() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setEnabled(true);
        properties.setToken("");
        properties.setTestGuildId("");

        List<String> errors = properties.validationErrors();

        assertThat(errors)
                .containsExactly("DEEFY_DISCORD_TOKEN is missing", "DEEFY_DISCORD_TEST_GUILD_ID is missing")
                .doesNotContain("token-is-only-a-test-value");
    }

    @Test
    void shouldRejectInvalidGuildId() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setToken("token-is-only-a-test-value");
        properties.setTestGuildId("not-a-guild-id");

        assertThat(properties.validationErrors())
                .containsExactly("DEEFY_DISCORD_TEST_GUILD_ID must be a Discord guild ID");
    }
}
