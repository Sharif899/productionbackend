package com.sharif899.prodbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * An order placed by a client. Money is in minor units (a long of
 * kobo/cents), never a float, so no rounding can ever appear in a
 * balance. The client reference is the business key: one reference,
 * one order, enforced by a unique constraint as well as by the
 * service check. {@code version} is optimistic locking, so two
 * concurrent updates to the same order cannot silently overwrite
 * each other.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "client_reference", nullable = false, unique = true, length = 64)
    private String clientReference;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "total_amount_minor", nullable = false)
    private long totalAmountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant updatedAt;

    /** For JPA only. */
    protected Order() {
    }

    public Order(String clientReference, String customerName, String currency, long totalAmountMinor) {
        this.id = UUID.randomUUID();
        this.clientReference = clientReference;
        this.customerName = customerName;
        this.currency = currency;
        this.totalAmountMinor = totalAmountMinor;
        this.status = OrderStatus.CREATED;
        // Set here, not only in @PrePersist: the callback fires when
        // Hibernate flushes, which is after the service has already
        // mapped the response, so a create response would otherwise
        // carry null timestamps. @PrePersist below stays as a backstop.
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** CREATED -> PAID. Anything else is a programming error; the service checks first. */
    public void markPaid() {
        if (status != OrderStatus.CREATED) {
            throw new IllegalStateException("Order " + id + " is " + status + " and cannot be paid");
        }
        status = OrderStatus.PAID;
    }

    /** CREATED -> CANCELLED. A paid order can never be cancelled. */
    public void cancel() {
        if (status != OrderStatus.CREATED) {
            throw new IllegalStateException("Order " + id + " is " + status + " and cannot be cancelled");
        }
        status = OrderStatus.CANCELLED;
    }

    public boolean isPayable() {
        return status == OrderStatus.CREATED;
    }

    public UUID getId() {
        return id;
    }

    public String getClientReference() {
        return clientReference;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCurrency() {
        return currency;
    }

    public long getTotalAmountMinor() {
        return totalAmountMinor;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
