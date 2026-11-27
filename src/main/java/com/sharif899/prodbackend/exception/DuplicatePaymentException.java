package com.sharif899.prodbackend.exception;

/** A payment with this reference already exists. */
public class DuplicatePaymentException extends RuntimeException {

    private final String reference;

    public DuplicatePaymentException(String reference) {
        super("A payment with reference '" + reference + "' already exists");
        this.reference = reference;
    }

    public String getReference() {
        return reference;
    }
}
