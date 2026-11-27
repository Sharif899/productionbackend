package com.sharif899.prodbackend.web.dto;

import com.sharif899.prodbackend.domain.Payment;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        String reference,
        String method,
        long amountMinor,
        String status,
        Instant createdAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getReference(),
                payment.getMethod().name(),
                payment.getAmountMinor(),
                payment.getStatus().name(),
                payment.getCreatedAt());
    }
}
