package br.com.deefy.discord;

public class DiscordAudioPreparationException extends RuntimeException {

    public DiscordAudioPreparationException(String message) {
        super(message);
    }

    public DiscordAudioPreparationException(String message, Throwable cause) {
        super(message, cause);
    }
}
