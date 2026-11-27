package com.sharif899.prodbackend.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One stored response for one idempotency key. A row is written in
 * the same transaction as the order or payment it records, so a
 * stored response always has the real resource behind it. The
 * (scope, key) primary key means a concurrent replay can never store
 * two responses for one key.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    // The Java field is called "key", but the column is
    // idempotency_key: KEY is a reserved word in SQL and in H2, and
    // a column literally called "key" makes Hibernate validation
    // disagree with the migration about the schema.
    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "scope",
                    column = @Column(name = "scope", nullable = false, length = 64)),
            @AttributeOverride(name = "key",
                    column = @Column(name = "idempotency_key", nullable = false, length = 255))
    })
    private IdempotencyKeyId id;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_body", nullable = false, length = 8192)
    private String responseBody;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant createdAt;

    /** For JPA only. */
    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(IdempotencyKeyId id, String requestHash, String responseBody, int statusCode) {
        this.id = id;
        this.requestHash = requestHash;
        this.responseBody = responseBody;
        this.statusCode = statusCode;
        this.createdAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public IdempotencyKeyId getId() {
        return id;
    }

    public String getScope() {
        return id == null ? null : id.getScope();
    }

    public String getKey() {
        return id == null ? null : id.getKey();
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
