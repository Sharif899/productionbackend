package com.sharif899.prodbackend.exception;

import java.util.UUID;

/**
 * A second order with the same client reference. The existing order
 * id is included when the service check found it; it is null when
 * the duplicate was caught by the database constraint in a race, in
 * which case the reference alone identifies the existing order.
 */
public class DuplicateOrderException extends RuntimeException {

    private final String clientReference;
    private final UUID existingOrderId;

    public DuplicateOrderException(String clientReference, UUID existingOrderId) {
        super("An order with client reference '" + clientReference + "' already exists");
        this.clientReference = clientReference;
        this.existingOrderId = existingOrderId;
    }

    public DuplicateOrderException(String clientReference) {
        this(clientReference, null);
    }

    public String getClientReference() {
        return clientReference;
    }

    public UUID getExistingOrderId() {
        return existingOrderId;
    }
}
