package com.sharif899.prodbackend.web;

import com.sharif899.prodbackend.domain.OrderStatus;
import com.sharif899.prodbackend.service.OrderService;
import com.sharif899.prodbackend.service.PaymentService;
import com.sharif899.prodbackend.web.dto.ApiResponse;
import com.sharif899.prodbackend.web.dto.CreateOrderRequest;
import com.sharif899.prodbackend.web.dto.OrderResponse;
import com.sharif899.prodbackend.web.dto.PageResponse;
import com.sharif899.prodbackend.web.dto.PaymentRequest;
import com.sharif899.prodbackend.web.dto.PaymentResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Orders and payments. Every mutating call accepts an optional
 * {@code Idempotency-Key} header: repeat a request with the same key
 * and the same body and you get the stored response back (200 with
 * {@code X-Idempotency-Replayed: true}) instead of a second order or
 * a second charge. Every response, success or error, uses the same
 * {@link ApiResponse} envelope.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {

    private final OrderService orderService;
    private final PaymentService paymentService;

    public OrderController(OrderService orderService, PaymentService paymentService) {
        this.orderService = orderService;
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var outcome = orderService.createOrder(request, idempotencyKey);
        var body = ApiResponse.ok(outcome.response());
        if (outcome.replayed()) {
            return ResponseEntity.ok()
                    .header("X-Idempotency-Replayed", "true")
                    .body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderResponse> getOrder(@PathVariable UUID id) {
        return ApiResponse.ok(orderService.getOrder(id));
    }

    @GetMapping
    public ApiResponse<PageResponse<OrderResponse>> listOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(orderService.listOrders(status, pageable));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<OrderResponse> cancelOrder(@PathVariable UUID id) {
        return ApiResponse.ok(orderService.cancelOrder(id));
    }

    @PostMapping("/{id}/payments")
    public ResponseEntity<ApiResponse<PaymentResponse>> processPayment(
            @PathVariable UUID id,
            @Valid @RequestBody PaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var outcome = paymentService.processPayment(id, request, idempotencyKey);
        var body = ApiResponse.ok(outcome.response());
        if (outcome.replayed()) {
            return ResponseEntity.ok()
                    .header("X-Idempotency-Replayed", "true")
                    .body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}/payments")
    public ApiResponse<List<PaymentResponse>> listPayments(@PathVariable UUID id) {
        return ApiResponse.ok(paymentService.listPayments(id));
    }
}
