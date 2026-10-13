package br.com.deefy.exception;

public class ProfilePhotoNotFoundException extends RuntimeException {
    public ProfilePhotoNotFoundException(String message) {
        super(message);
    }
}
