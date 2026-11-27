package com.sharif899.prodbackend.web.dto;

import com.sharif899.prodbackend.domain.Order;

import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String clientReference,
        String customerName,
        String currency,
        long totalAmountMinor,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getClientReference(),
                order.getCustomerName(),
                order.getCurrency(),
                order.getTotalAmountMinor(),
                order.getStatus().name(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
