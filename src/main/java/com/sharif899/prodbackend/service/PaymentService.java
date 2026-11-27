package com.sharif899.prodbackend.service;

import com.sharif899.prodbackend.domain.Order;
import com.sharif899.prodbackend.domain.Payment;
import com.sharif899.prodbackend.exception.DuplicatePaymentException;
import com.sharif899.prodbackend.exception.IdempotencyConflictException;
import com.sharif899.prodbackend.exception.InvalidOrderStateException;
import com.sharif899.prodbackend.exception.OrderNotFoundException;
import com.sharif899.prodbackend.exception.PaymentAmountMismatchException;
import com.sharif899.prodbackend.repository.OrderRepository;
import com.sharif899.prodbackend.repository.PaymentRepository;
import com.sharif899.prodbackend.web.dto.PaymentRequest;
import com.sharif899.prodbackend.web.dto.PaymentResponse;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.List;
import java.util.UUID;

/**
 * Payments against orders. The rules:
 *
 * <ul>
 *   <li>An order is paid in full, so the payment amount must equal
 *       the order total; anything else is a 422.</li>
 *   <li>Only a CREATED order can be paid. Paying a PAID or CANCELLED
 *       order is a 409.</li>
 *   <li>One payment per order, enforced by the service check and by
 *       the unique constraint on {@code payments.order_id}. A second
 *       payment can never charge twice.</li>
 *   <li>A payment reference is unique. A repeated reference is a
 *       409 with the existing payment.</li>
 *   <li>Idempotency works exactly as for order creation: a retry
 *       with the same key and payload returns the stored payment
 *       (200); a different payload under the same key is a 422.</li>
 * </ul>
 *
 * <p>The payment row and the order's move to PAID happen in one
 * transaction, so an order can never be marked paid without a
 * payment row, or have a payment row while still CREATED.
 */
@Service
@Transactional
public class PaymentService {

    private static final String PAYMENT_SCOPE = "process-payment";

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final IdempotencyService idempotency;

    public PaymentService(OrderRepository orders, PaymentRepository payments,
                          IdempotencyService idempotency) {
        this.orders = orders;
        this.payments = payments;
        this.idempotency = idempotency;
    }

    /** Result of a payment call: the payment, and whether it was a replay. */
    public record PaymentOutcome(PaymentResponse response, boolean replayed) {
    }

    @CacheEvict(value = "orders", key = "#orderId")
    public PaymentOutcome processPayment(UUID orderId, PaymentRequest request, String idempotencyKey) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        String hash = null;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            hash = idempotency.hash(new PaymentHashInput(orderId, request));
            var existing = idempotency.lookup(PAYMENT_SCOPE, idempotencyKey.trim());
            if (existing.isPresent()) {
                var record = existing.get();
                if (!record.getRequestHash().equals(hash)) {
                    throw new IdempotencyConflictException(idempotencyKey.trim());
                }
                return new PaymentOutcome(
                        idempotency.fromJson(record.getResponseBody(), PaymentResponse.class), true);
            }
        }

        PaymentResponse created = processPaymentInternal(order, request);

        if (hash != null) {
            try {
                idempotency.store(PAYMENT_SCOPE, idempotencyKey.trim(), hash,
                        idempotency.toJson(created), 201);
            } catch (DataIntegrityViolationException e) {
                var stored = idempotency.lookup(PAYMENT_SCOPE, idempotencyKey.trim())
                        .orElseThrow(() -> e);
                if (!stored.getRequestHash().equals(hash)) {
                    throw new IdempotencyConflictException(idempotencyKey.trim());
                }
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return new PaymentOutcome(
                        idempotency.fromJson(stored.getResponseBody(), PaymentResponse.class), true);
            }
        }
        return new PaymentOutcome(created, false);
    }

    private PaymentResponse processPaymentInternal(Order order, PaymentRequest request) {
        if (!order.isPayable()) {
            throw new InvalidOrderStateException(
                    "Order " + order.getId() + " is " + order.getStatus() + " and cannot be paid");
        }
        if (request.amountMinor() != order.getTotalAmountMinor()) {
            throw new PaymentAmountMismatchException(order.getTotalAmountMinor(), request.amountMinor());
        }
        var duplicate = payments.findByReference(request.reference());
        if (duplicate.isPresent()) {
            throw new DuplicatePaymentException(request.reference());
        }
        if (payments.existsByOrder_Id(order.getId())) {
            throw new InvalidOrderStateException(
                    "Order " + order.getId() + " already has a payment");
        }
        var payment = new Payment(order, request.reference(), request.method(), request.amountMinor());
        try {
            payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            // Lost a race on the payment reference or the one-payment rule.
            throw new DuplicatePaymentException(request.reference());
        }
        order.markPaid();
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listPayments(UUID orderId) {
        if (!orders.existsById(orderId)) {
            throw new OrderNotFoundException(orderId);
        }
        return payments.findByOrder_Id(orderId).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    /**
     * The value hashed for a payment idempotency key. The order id is
     * part of the hash, so the same key on a different order is a
     * different request and correctly conflicts.
     */
    private record PaymentHashInput(UUID orderId, PaymentRequest request) {
    }
}
