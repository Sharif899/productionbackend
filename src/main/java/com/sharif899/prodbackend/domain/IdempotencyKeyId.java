package com.sharif899.prodbackend.domain;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of {@link IdempotencyRecord}: the endpoint scope plus the client key. */
public class IdempotencyKeyId implements Serializable {

    private String scope;
    private String key;

    /** For JPA only. */
    protected IdempotencyKeyId() {
    }

    public IdempotencyKeyId(String scope, String key) {
        this.scope = scope;
        this.key = key;
    }

    public String getScope() {
        return scope;
    }

    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        IdempotencyKeyId that = (IdempotencyKeyId) other;
        return Objects.equals(scope, that.scope) && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, key);
    }
}
