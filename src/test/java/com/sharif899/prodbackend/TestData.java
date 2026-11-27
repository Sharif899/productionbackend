package com.sharif899.prodbackend;

import com.sharif899.prodbackend.domain.PaymentMethod;
import com.sharif899.prodbackend.web.dto.CreateOrderRequest;
import com.sharif899.prodbackend.web.dto.PaymentRequest;

import java.util.UUID;

/** Factories for test data. Every reference and key is unique, because
 * the test classes share one in-memory database. */
public final class TestData {

    private TestData() {
    }

    public static String uniqueRef(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String uniqueKey() {
        return "key-" + UUID.randomUUID();
    }

    public static CreateOrderRequest orderRequest(String ref) {
        return new CreateOrderRequest(ref, "Ada Lovelace", "NGN", 250_000L);
    }

    public static CreateOrderRequest orderRequest() {
        return orderRequest(uniqueRef("ORD"));
    }

    public static PaymentRequest paymentRequest(String ref, long amountMinor) {
        return new PaymentRequest(ref, PaymentMethod.CARD, amountMinor);
    }

    public static PaymentRequest paymentRequest(long amountMinor) {
        return paymentRequest(uniqueRef("PAY"), amountMinor);
    }
}
