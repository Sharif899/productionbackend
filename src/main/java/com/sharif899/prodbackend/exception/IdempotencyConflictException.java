package com.sharif899.prodbackend.exception;

/** The same idempotency key was reused with a different request payload. */
public class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key '" + idempotencyKey + "' was already used with a different request");
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
