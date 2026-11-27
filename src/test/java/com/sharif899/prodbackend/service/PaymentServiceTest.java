package com.sharif899.prodbackend.service;

import com.sharif899.prodbackend.TestData;
import com.sharif899.prodbackend.exception.DuplicatePaymentException;
import com.sharif899.prodbackend.exception.IdempotencyConflictException;
import com.sharif899.prodbackend.exception.InvalidOrderStateException;
import com.sharif899.prodbackend.exception.OrderNotFoundException;
import com.sharif899.prodbackend.exception.PaymentAmountMismatchException;
import com.sharif899.prodbackend.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentServiceTest {

    @Autowired
    private OrderService orders;

    @Autowired
    private PaymentService payments;

    @Autowired
    private PaymentRepository paymentRepository;

    private UUID createOrder() {
        return orders.createOrder(TestData.orderRequest(), null).response().id();
    }

    @Test
    void fullPaymentSucceedsAndMarksTheOrderPaid() {
        UUID orderId = createOrder();
        var outcome = payments.processPayment(orderId, TestData.paymentRequest(250_000L), null);

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.response().status()).isEqualTo("SUCCEEDED");
        assertThat(outcome.response().orderId()).isEqualTo(orderId);
        assertThat(outcome.response().createdAt()).isNotNull();
        assertThat(orders.getOrder(orderId).status()).isEqualTo("PAID");
        assertThat(payments.listPayments(orderId)).hasSize(1);
    }

    @Test
    void wrongAmountIsA422AndLeavesNoPaymentRow() {
        UUID orderId = createOrder();

        assertThatThrownBy(() -> payments.processPayment(orderId, TestData.paymentRequest(100L), null))
                .isInstanceOf(PaymentAmountMismatchException.class)
                .satisfies(e -> {
                    var mismatch = (PaymentAmountMismatchException) e;
                    assertThat(mismatch.getExpectedMinor()).isEqualTo(250_000L);
                    assertThat(mismatch.getActualMinor()).isEqualTo(100L);
                });
        assertThat(orders.getOrder(orderId).status()).isEqualTo("CREATED");
        assertThat(payments.listPayments(orderId)).isEmpty();
    }

    @Test
    void payingAPaidOrderIsAConflict() {
        UUID orderId = createOrder();
        payments.processPayment(orderId, TestData.paymentRequest(250_000L), null);

        assertThatThrownBy(() -> payments.processPayment(orderId, TestData.paymentRequest(250_000L), null))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(payments.listPayments(orderId)).hasSize(1);
    }

    @Test
    void payingACancelledOrderIsAConflict() {
        UUID orderId = createOrder();
        orders.cancelOrder(orderId);

        assertThatThrownBy(() -> payments.processPayment(orderId, TestData.paymentRequest(250_000L), null))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(payments.listPayments(orderId)).isEmpty();
    }

    @Test
    void duplicatePaymentReferenceIsAConflict() {
        UUID orderId = createOrder();
        String reference = TestData.uniqueRef("PAY");
        payments.processPayment(orderId, TestData.paymentRequest(reference, 250_000L), null);

        UUID secondOrderId = createOrder();
        assertThatThrownBy(() -> payments.processPayment(
                secondOrderId, TestData.paymentRequest(reference, 250_000L), null))
                .isInstanceOf(DuplicatePaymentException.class);
        assertThat(payments.listPayments(secondOrderId)).isEmpty();
    }

    @Test
    void replayWithTheSameKeyAndPayloadReturnsTheStoredPayment() {
        UUID orderId = createOrder();
        String key = TestData.uniqueKey();
        var request = TestData.paymentRequest(250_000L);

        var first = payments.processPayment(orderId, request, key);
        var second = payments.processPayment(orderId, request, key);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.response().id()).isEqualTo(first.response().id());
        assertThat(payments.listPayments(orderId)).hasSize(1);
    }

    @Test
    void sameKeyWithADifferentPayloadIsAConflict() {
        UUID orderId = createOrder();
        String key = TestData.uniqueKey();
        payments.processPayment(orderId, TestData.paymentRequest(250_000L), key);

        assertThatThrownBy(() -> payments.processPayment(
                orderId, TestData.paymentRequest(250_000L), key))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void payingAMissingOrderIsNotFound() {
        assertThatThrownBy(() -> payments.processPayment(
                UUID.randomUUID(), TestData.paymentRequest(250_000L), null))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void concurrentPaymentsOnOneOrderLeaveExactlyOnePayment() throws Exception {
        UUID orderId = createOrder();
        int threads = 2;
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<PaymentService.PaymentOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return payments.processPayment(orderId, TestData.paymentRequest(250_000L), null);
                }));
            }
            ready.await();
            start.countDown();
            int succeeded = 0;
            int conflicts = 0;
            for (Future<PaymentService.PaymentOutcome> future : futures) {
                try {
                    future.get();
                    succeeded++;
                } catch (Exception e) {
                    assertThat(e.getCause()).isInstanceOfAny(
                            InvalidOrderStateException.class, DuplicatePaymentException.class);
                    conflicts++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(payments.listPayments(orderId)).hasSize(1);
            assertThat(orders.getOrder(orderId).status()).isEqualTo("PAID");
            assertThat(paymentRepository.count()).isGreaterThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
