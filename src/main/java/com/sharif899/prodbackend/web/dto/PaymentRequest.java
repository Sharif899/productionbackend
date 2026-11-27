package com.sharif899.prodbackend.web.dto;

import com.sharif899.prodbackend.domain.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Pay an order in full. The amount must equal the order total. */
public record PaymentRequest(
        @NotBlank(message = "reference is required")
        @Size(max = 64, message = "reference must be at most 64 characters")
        String reference,

        @NotNull(message = "method is required")
        PaymentMethod method,

        @Positive(message = "amountMinor must be greater than zero")
        long amountMinor) {
}
