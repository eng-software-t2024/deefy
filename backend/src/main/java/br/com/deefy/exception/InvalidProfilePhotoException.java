package br.com.deefy.exception;

public class InvalidProfilePhotoException extends RuntimeException {
    public InvalidProfilePhotoException(String message) {
        super(message);
    }

    public InvalidProfilePhotoException(String message, Throwable cause) {
        super(message, cause);
    }
}
