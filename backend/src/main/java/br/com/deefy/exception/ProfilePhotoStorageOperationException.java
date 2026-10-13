package br.com.deefy.exception;

public class ProfilePhotoStorageOperationException extends RuntimeException {
    public ProfilePhotoStorageOperationException(String message) {
        super(message);
    }

    public ProfilePhotoStorageOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
