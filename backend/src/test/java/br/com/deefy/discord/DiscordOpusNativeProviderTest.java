package br.com.deefy.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DiscordOpusNativeProviderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldPreloadOpusFromExplicitRuntimePathBeforeJdaAudioCheck() throws Exception {
        Path library = Files.writeString(tempDirectory.resolve("libopus.so"), "test-native");
        AtomicReference<String> loadedPath = new AtomicReference<>();
        DiscordOpusNativeProvider provider = new DiscordOpusNativeProvider(
                library.toAbsolutePath(),
                path -> {
                    loadedPath.set(path);
                    return true;
                },
                () -> true
        );

        provider.loadRequired();

        assertThat(loadedPath).hasValue(library.toAbsolutePath().toString());
    }

    @Test
    void shouldFailClosedBeforeDiscordStartupWhenExplicitOpusPathIsMissing() {
        DiscordOpusNativeProvider provider = new DiscordOpusNativeProvider(
                tempDirectory.resolve("missing-libopus.so").toAbsolutePath(),
                ignored -> true,
                () -> true
        );

        assertThatThrownBy(provider::loadRequired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Required Opus native library is not available");
    }

    @Test
    void shouldFailClosedWhenJdaCannotUseThePreloadedOpusLibrary() throws Exception {
        Path library = Files.writeString(tempDirectory.resolve("libopus.so"), "test-native");
        DiscordOpusNativeProvider provider = new DiscordOpusNativeProvider(
                library.toAbsolutePath(),
                ignored -> true,
                () -> false
        );

        assertThatThrownBy(provider::loadRequired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Required Opus native library could not be initialized");
    }

    @Test
    void shouldLoadRealOpusNativeFromExplicitPathWhenSmokeIsEnabled() {
        assumeTrue(Boolean.getBoolean("deefy.discord.opus.native-smoke"));
        String configuredPath = System.getProperty("deefy.discord.opus-library-path", "");
        assumeTrue(!configuredPath.isBlank());

        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setOpusLibraryPath(configuredPath);

        new DiscordOpusNativeProvider(properties).loadRequired();
    }
}
