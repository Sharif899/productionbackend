package com.sharif899.prodbackend.service;

import com.sharif899.prodbackend.TestData;
import com.sharif899.prodbackend.web.dto.OrderResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class IdempotencyServiceTest {

    @Autowired
    private IdempotencyService idempotency;

    @Test
    void hashIsDeterministicForEqualPayloads() {
        var first = TestData.orderRequest("ORD-HASH-1");
        var second = TestData.orderRequest("ORD-HASH-1");
        assertThat(idempotency.hash(first)).isEqualTo(idempotency.hash(second));
    }

    @Test
    void hashDiffersWhenAnyFieldDiffers() {
        String base = idempotency.hash(TestData.orderRequest("ORD-HASH-2"));
        assertThat(idempotency.hash(TestData.orderRequest("ORD-HASH-3"))).isNotEqualTo(base);
        assertThat(idempotency.hash(new com.sharif899.prodbackend.web.dto.CreateOrderRequest(
                "ORD-HASH-2", "Grace Hopper", "NGN", 250_000L))).isNotEqualTo(base);
        assertThat(idempotency.hash(new com.sharif899.prodbackend.web.dto.CreateOrderRequest(
                "ORD-HASH-2", "Ada Lovelace", "USD", 250_000L))).isNotEqualTo(base);
        assertThat(idempotency.hash(new com.sharif899.prodbackend.web.dto.CreateOrderRequest(
                "ORD-HASH-2", "Ada Lovelace", "NGN", 100L))).isNotEqualTo(base);
    }

    @Test
    void hashIs64HexCharacters() {
        assertThat(idempotency.hash(TestData.orderRequest())).matches("[0-9a-f]{64}");
    }

    @Test
    void lookupReturnsEmptyForAnUnusedKey() {
        assertThat(idempotency.lookup("create-order", TestData.uniqueKey())).isEmpty();
    }

    @Test
    void storeThenLookupReturnsTheRecord() {
        String key = TestData.uniqueKey();
        String hash = idempotency.hash(TestData.orderRequest());
        String body = "{\"id\":\"" + UUID.randomUUID() + "\"}";

        idempotency.store("create-order", key, hash, body, 201);

        var found = idempotency.lookup("create-order", key);
        assertThat(found).isPresent();
        assertThat(found.get().getRequestHash()).isEqualTo(hash);
        assertThat(found.get().getResponseBody()).isEqualTo(body);
        assertThat(found.get().getStatusCode()).isEqualTo(201);
    }

    @Test
    void storingTheSameKeyTwiceFailsOnTheConstraint() {
        String key = TestData.uniqueKey();
        String hash = idempotency.hash(TestData.orderRequest());
        idempotency.store("create-order", key, hash, "{}", 201);

        assertThatThrownBy(() ->
                idempotency.store("create-order", key, hash, "{}", 201))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameKeyInAnotherScopeIsIndependent() {
        String key = TestData.uniqueKey();
        String hash = idempotency.hash(TestData.orderRequest());
        idempotency.store("create-order", key, hash, "{}", 201);

        // No exception: the primary key is (scope, key), not the key alone.
        idempotency.store("process-payment", key, hash, "{}", 201);
        assertThat(idempotency.lookup("process-payment", key)).isPresent();
    }

    @Test
    void jsonRoundTripForAnOrderResponse() {
        var response = new OrderResponse(UUID.randomUUID(), "ORD-RT-1", "Ada Lovelace",
                "NGN", 250_000L, "CREATED", Instant.now(), Instant.now());
        String json = idempotency.toJson(response);
        assertThat(idempotency.fromJson(json, OrderResponse.class)).isEqualTo(response);
    }
}
