package br.com.deefy.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordBotPropertiesTest {

    @Test
    void shouldAllowNormalModeWithoutTestGuild() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setEnabled(true);
        properties.setToken("fixture-value");
        properties.setTestGuildId("ignored-outside-test-mode");

        assertThat(properties.validationErrors()).isEmpty();
        assertThat(properties.isGuildAllowed("123456789012345678")).isTrue();
        assertThat(properties.isGuildAllowed("987654321098765432")).isTrue();
    }

    @Test
    void shouldReportMissingTokenWithoutRequiringGuildInNormalMode() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setEnabled(true);
        properties.setToken("");
        properties.setTestGuildId("");

        List<String> errors = properties.validationErrors();

        assertThat(errors)
                .containsExactly("DEEFY_DISCORD_TOKEN or DEEFY_DISCORD_TOKEN_FILE is missing")
                .doesNotContain("fixture-value");
    }

    @Test
    void shouldRequireAndEnforceGuildOnlyInExplicitTestMode() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setToken("fixture-value");
        properties.setTestMode(true);

        assertThat(properties.validationErrors())
                .containsExactly("DEEFY_DISCORD_TEST_GUILD_ID is missing");

        properties.setTestGuildId("123456789012345678");

        assertThat(properties.validationErrors()).isEmpty();
        assertThat(properties.isGuildAllowed("123456789012345678")).isTrue();
        assertThat(properties.isGuildAllowed("987654321098765432")).isFalse();
    }

    @Test
    void shouldRejectInvalidGuildId() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setToken("fixture-value");
        properties.setTestMode(true);
        properties.setTestGuildId("not-a-guild-id");

        assertThat(properties.validationErrors())
                .containsExactly("DEEFY_DISCORD_TEST_GUILD_ID must be a Discord guild ID");
    }

    @Test
    void shouldResolveConfiguredPrivateFile(@TempDir Path directory) throws Exception {
        Path tokenFile = directory.resolve("discord-secret-fixture");
        Files.writeString(tokenFile, "fixture-value\n");
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setTokenFile(tokenFile.toString());

        assertThat(properties.validationErrors()).isEmpty();
        assertThat(properties.resolveToken()).isEqualTo("fixture-value");
        assertThat(properties.getToken()).isEmpty();
    }
}
