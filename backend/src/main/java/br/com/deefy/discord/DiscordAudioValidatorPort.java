package br.com.deefy.discord;

import java.nio.file.Path;

@FunctionalInterface
public interface DiscordAudioValidatorPort {

    DiscordValidatedAudioMetadata validate(Path audioFile);
}
