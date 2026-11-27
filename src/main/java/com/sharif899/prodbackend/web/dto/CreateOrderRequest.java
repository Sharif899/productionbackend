package com.sharif899.prodbackend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Create an order. Amounts are in minor units (kobo/cents), never floats. */
public record CreateOrderRequest(
        @NotBlank(message = "clientReference is required")
        @Size(max = 64, message = "clientReference must be at most 64 characters")
        String clientReference,

        @NotBlank(message = "customerName is required")
        @Size(max = 200, message = "customerName must be at most 200 characters")
        String customerName,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO 4217 code")
        String currency,

        @Positive(message = "totalAmountMinor must be greater than zero")
        long totalAmountMinor) {
}
