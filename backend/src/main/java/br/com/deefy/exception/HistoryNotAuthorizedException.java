package br.com.deefy.exception;


public class HistoryNotAuthorizedException extends RuntimeException {
    public HistoryNotAuthorizedException(String message) {
        super(message);
    }
}
