package br.com.deefy.discord;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "deefy.discord")
public class DiscordBotProperties {

    private boolean enabled;
    private String token = "";
    private String tokenFile = "";
    private boolean testMode;
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

    public String getTokenFile() {
        return tokenFile;
    }

    public void setTokenFile(String tokenFile) {
        this.tokenFile = tokenFile;
    }

    public String getTestGuildId() {
        return testGuildId;
    }

    public void setTestGuildId(String testGuildId) {
        this.testGuildId = testGuildId;
    }

    public boolean isTestMode() {
        return testMode;
    }

    public void setTestMode(boolean testMode) {
        this.testMode = testMode;
    }

    public boolean isGuildAllowed(String guildId) {
        if (!testMode) {
            return true;
        }
        return guildId != null
                && testGuildId != null
                && guildId.equals(testGuildId.trim());
    }

    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();

        if ((token == null || token.isBlank()) && (tokenFile == null || tokenFile.isBlank())) {
            errors.add("DEEFY_DISCORD_TOKEN or DEEFY_DISCORD_TOKEN_FILE is missing");
        }

        if (testMode) {
            if (testGuildId == null || testGuildId.isBlank()) {
                errors.add("DEEFY_DISCORD_TEST_GUILD_ID is missing");
            } else if (!isUnsignedLong(testGuildId.trim())) {
                errors.add("DEEFY_DISCORD_TEST_GUILD_ID must be a Discord guild ID");
            }
        }

        return errors;
    }

    public String resolveToken() throws IOException {
        if (tokenFile != null && !tokenFile.isBlank()) {
            return Files.readString(Path.of(tokenFile.trim())).trim();
        }
        return token == null ? "" : token.trim();
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
