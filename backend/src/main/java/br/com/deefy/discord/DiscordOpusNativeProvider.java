package br.com.deefy.discord;

import club.minnced.opus.util.OpusLibrary;
import net.dv8tion.jda.api.audio.AudioNatives;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

@Component
public class DiscordOpusNativeProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordOpusNativeProvider.class);

    private final Path libraryPath;
    private final NativeLoader nativeLoader;
    private final BooleanSupplier audioSupportCheck;

    @Autowired
    public DiscordOpusNativeProvider(DiscordBotProperties properties) {
        this(
                Path.of(properties.getOpusLibraryPath()).toAbsolutePath().normalize(),
                path -> {
                    OpusLibrary.loadFrom(path);
                    return OpusLibrary.isInitialized();
                },
                AudioNatives::ensureOpus
        );
    }

    DiscordOpusNativeProvider(
            Path libraryPath,
            NativeLoader nativeLoader,
            BooleanSupplier audioSupportCheck
    ) {
        this.libraryPath = libraryPath;
        this.nativeLoader = nativeLoader;
        this.audioSupportCheck = audioSupportCheck;
    }

    void loadRequired() {
        if (!libraryPath.isAbsolute()
                || !Files.isRegularFile(libraryPath)
                || !Files.isReadable(libraryPath)) {
            throw new IllegalStateException("Required Opus native library is not available");
        }

        try {
            if (!nativeLoader.load(libraryPath.toString()) || !audioSupportCheck.getAsBoolean()) {
                throw new IllegalStateException("Required Opus native library could not be initialized");
            }
            LOGGER.info("Discord Opus native initialized from the immutable runtime image");
        } catch (LinkageError | RuntimeException exception) {
            throw new IllegalStateException("Required Opus native library could not be initialized", exception);
        }
    }

    @FunctionalInterface
    interface NativeLoader {
        boolean load(String absolutePath);
    }
}
