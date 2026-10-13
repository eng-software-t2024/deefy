package br.com.deefy.exception;

public class ProfilePhotoTooLargeException extends RuntimeException {
    public ProfilePhotoTooLargeException(String message) {
        super(message);
    }
}
