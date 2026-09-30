package br.com.deefy.discord;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "deefy.discord")
public class DiscordBotProperties {

    private boolean enabled;
    private String token = "";
    private String testGuildId = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTestGuildId() {
        return testGuildId;
    }

    public void setTestGuildId(String testGuildId) {
        this.testGuildId = testGuildId;
    }

    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();

        if (token == null || token.isBlank()) {
            errors.add("DEEFY_DISCORD_TOKEN is missing");
        }

        if (testGuildId == null || testGuildId.isBlank()) {
            errors.add("DEEFY_DISCORD_TEST_GUILD_ID is missing");
        } else if (!isUnsignedLong(testGuildId.trim())) {
            errors.add("DEEFY_DISCORD_TEST_GUILD_ID must be a Discord guild ID");
        }

        return errors;
    }

    private boolean isUnsignedLong(String value) {
        try {
            Long.parseUnsignedLong(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
