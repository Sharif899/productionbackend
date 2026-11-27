package com.sharif899.prodbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A payment recorded against an order. Payments are immutable ledger
 * rows: once written they are never updated, only read. An order is
 * paid in full, so there is at most one payment per order (enforced
 * by the unique constraint on order_id as well as by the service).
 *
 * <p>There is no external payment gateway here. "Process payment"
 * records the payment in this ledger and moves the order to PAID;
 * a decline is expressed as a 4xx error, not as a FAILED row.
 * {@link PaymentStatus#FAILED} is the ledger status a settlement or
 * chargeback flow would write, and it is not produced by this API.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant createdAt;

    /** For JPA only. */
    protected Payment() {
    }

    public Payment(Order order, String reference, PaymentMethod method, long amountMinor) {
        this.id = UUID.randomUUID();
        this.order = order;
        this.reference = reference;
        this.method = method;
        this.amountMinor = amountMinor;
        this.status = PaymentStatus.SUCCEEDED;
        // Same reason as Order: @PrePersist fires at flush, after
        // the response is mapped, so the timestamp is set here.
        this.createdAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public UUID getOrderId() {
        return order == null ? null : order.getId();
    }

    public String getReference() {
        return reference;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
