package com.sharif899.prodbackend.exception;

/** The order is in a state that does not allow the requested transition. */
public class InvalidOrderStateException extends RuntimeException {

    public InvalidOrderStateException(String message) {
        super(message);
    }
}
