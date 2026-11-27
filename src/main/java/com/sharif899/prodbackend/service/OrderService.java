package com.sharif899.prodbackend.service;

import com.sharif899.prodbackend.domain.Order;
import com.sharif899.prodbackend.domain.OrderStatus;
import com.sharif899.prodbackend.exception.DuplicateOrderException;
import com.sharif899.prodbackend.exception.IdempotencyConflictException;
import com.sharif899.prodbackend.exception.InvalidOrderStateException;
import com.sharif899.prodbackend.exception.OrderNotFoundException;
import com.sharif899.prodbackend.repository.OrderRepository;
import com.sharif899.prodbackend.web.dto.CreateOrderRequest;
import com.sharif899.prodbackend.web.dto.OrderResponse;
import com.sharif899.prodbackend.web.dto.PageResponse;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.UUID;

/**
 * The order workflow. Owns the rules a real order intake depends on:
 *
 * <ul>
 *   <li><b>Idempotency</b> — a retried POST with the same
 *       {@code Idempotency-Key} returns the stored response (200)
 *       instead of creating a second order. A different payload
 *       under the same key is a 422.</li>
 *   <li><b>Duplicate detection</b> — one client reference, one
 *       order. A second submission returns 409 with the existing
 *       order instead of creating a duplicate. The service check
 *       handles the common case; the database unique constraint
 *       catches the race, and the loser gets a 409, never a second
 *       row.</li>
 *   <li><b>State machine</b> — CREATED can go to PAID (via a
 *       payment) or CANCELLED. A paid or cancelled order cannot
 *       change state again.</li>
 * </ul>
 *
 * <p>On the idempotency race: if storing the key fails because
 * another request claimed the key first, the stored record decides.
 * Same payload means the other request already did the work, so
 * this transaction is marked rollback-only (its order is discarded)
 * and the stored response is returned as a replay. Different
 * payload means a 422, and this transaction rolls back with it.
 */
@Service
@Transactional
public class OrderService {

    private static final String CREATE_ORDER_SCOPE = "create-order";

    private final OrderRepository orders;
    private final IdempotencyService idempotency;

    public OrderService(OrderRepository orders, IdempotencyService idempotency) {
        this.orders = orders;
        this.idempotency = idempotency;
    }

    /** Result of a create call: the order, and whether it was a replay of an earlier request. */
    public record CreateOutcome(OrderResponse response, boolean replayed) {
    }

    public CreateOutcome createOrder(CreateOrderRequest request, String idempotencyKey) {
        String hash = null;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            hash = idempotency.hash(request);
            var existing = idempotency.lookup(CREATE_ORDER_SCOPE, idempotencyKey.trim());
            if (existing.isPresent()) {
                var record = existing.get();
                if (!record.getRequestHash().equals(hash)) {
                    throw new IdempotencyConflictException(idempotencyKey.trim());
                }
                return new CreateOutcome(
                        idempotency.fromJson(record.getResponseBody(), OrderResponse.class), true);
            }
        }

        OrderResponse created = createOrderInternal(request);

        if (hash != null) {
            try {
                idempotency.store(CREATE_ORDER_SCOPE, idempotencyKey.trim(), hash,
                        idempotency.toJson(created), 201);
            } catch (DataIntegrityViolationException e) {
                // Lost a race on the key: the stored record decides.
                var stored = idempotency.lookup(CREATE_ORDER_SCOPE, idempotencyKey.trim())
                        .orElseThrow(() -> e);
                if (!stored.getRequestHash().equals(hash)) {
                    throw new IdempotencyConflictException(idempotencyKey.trim());
                }
                // Same payload: the other request already did the work.
                // Discard this transaction's order and return the stored response.
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return new CreateOutcome(
                        idempotency.fromJson(stored.getResponseBody(), OrderResponse.class), true);
            }
        }
        return new CreateOutcome(created, false);
    }

    private OrderResponse createOrderInternal(CreateOrderRequest request) {
        // Duplicate detection, common case: the reference is already taken.
        var duplicate = orders.findByClientReference(request.clientReference());
        if (duplicate.isPresent()) {
            throw new DuplicateOrderException(request.clientReference(), duplicate.get().getId());
        }
        var order = new Order(request.clientReference(), request.customerName(),
                request.currency(), request.totalAmountMinor());
        try {
            orders.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            // Lost a race: another request created this reference first.
            // The transaction is marked rollback-only by the failure, so
            // the existing order cannot be read here; the reference alone
            // identifies it for the client.
            throw new DuplicateOrderException(request.clientReference());
        }
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "orders", key = "#id")
    public OrderResponse getOrder(UUID id) {
        return OrderResponse.from(loadOrder(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listOrders(OrderStatus status, Pageable pageable) {
        Page<Order> page = status == null
                ? orders.findAll(pageable)
                : orders.findByStatus(status, pageable);
        return PageResponse.from(page, OrderResponse::from);
    }

    @CacheEvict(value = "orders", key = "#id")
    public OrderResponse cancelOrder(UUID id) {
        Order order = loadOrder(id);
        if (!order.isPayable()) {
            throw new InvalidOrderStateException(
                    "Order " + id + " is " + order.getStatus() + " and cannot be cancelled");
        }
        order.cancel();
        return OrderResponse.from(order);
    }

    /** Load an order or fail with 404. Package-private so PaymentService can reuse it. */
    Order loadOrder(UUID id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }
}
