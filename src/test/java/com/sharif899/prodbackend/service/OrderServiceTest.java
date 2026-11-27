package com.sharif899.prodbackend.service;

import com.sharif899.prodbackend.TestData;
import com.sharif899.prodbackend.domain.OrderStatus;
import com.sharif899.prodbackend.exception.DuplicateOrderException;
import com.sharif899.prodbackend.exception.IdempotencyConflictException;
import com.sharif899.prodbackend.exception.InvalidOrderStateException;
import com.sharif899.prodbackend.exception.OrderNotFoundException;
import com.sharif899.prodbackend.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

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
class OrderServiceTest {

    @Autowired
    private OrderService orders;

    @Autowired
    private OrderRepository repository;

    @Test
    void createsAnOrderInCreatedState() {
        var request = TestData.orderRequest();
        var outcome = orders.createOrder(request, null);

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.response().clientReference()).isEqualTo(request.clientReference());
        assertThat(outcome.response().status()).isEqualTo("CREATED");
        assertThat(outcome.response().totalAmountMinor()).isEqualTo(250_000L);
        // Timestamps are set in the constructor, so the create
        // response carries them, not just a later GET.
        assertThat(outcome.response().createdAt()).isNotNull();
        assertThat(outcome.response().updatedAt()).isNotNull();
        assertThat(repository.findByClientReference(request.clientReference())).isPresent();
    }

    @Test
    void replayWithTheSameKeyAndPayloadReturnsTheStoredOrder() {
        var request = TestData.orderRequest();
        String key = TestData.uniqueKey();

        var first = orders.createOrder(request, key);
        var second = orders.createOrder(request, key);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.response().id()).isEqualTo(first.response().id());
        assertThat(repository.findAll().stream()
                .filter(o -> o.getClientReference().equals(request.clientReference()))
                .count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentPayloadIsAConflict() {
        String key = TestData.uniqueKey();
        orders.createOrder(TestData.orderRequest(), key);

        var different = TestData.orderRequest(); // different reference, different payload
        assertThatThrownBy(() -> orders.createOrder(different, key))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void duplicateReferenceIsAConflictWithTheExistingOrderId() {
        var request = TestData.orderRequest();
        var created = orders.createOrder(request, null);

        assertThatThrownBy(() -> orders.createOrder(request, null))
                .isInstanceOf(DuplicateOrderException.class)
                .satisfies(e -> {
                    var duplicate = (DuplicateOrderException) e;
                    assertThat(duplicate.getExistingOrderId()).isEqualTo(created.response().id());
                    assertThat(duplicate.getClientReference()).isEqualTo(request.clientReference());
                });
        assertThat(repository.findAll().stream()
                .filter(o -> o.getClientReference().equals(request.clientReference()))
                .count()).isEqualTo(1);
    }

    @Test
    void concurrentCreatesWithTheSameReferenceLeaveExactlyOneOrder() throws Exception {
        var request = TestData.orderRequest();
        int threads = 2;
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<OrderService.CreateOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return orders.createOrder(request, null);
                }));
            }
            ready.await();
            start.countDown();
            int created = 0;
            int duplicates = 0;
            for (Future<OrderService.CreateOutcome> future : futures) {
                try {
                    future.get();
                    created++;
                } catch (Exception e) {
                    assertThat(e.getCause()).isInstanceOf(DuplicateOrderException.class);
                    duplicates++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(duplicates).isEqualTo(1);
            assertThat(repository.findAll().stream()
                    .filter(o -> o.getClientReference().equals(request.clientReference()))
                    .count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentReplaysWithTheSameKeyLeaveExactlyOneOrder() throws Exception {
        var request = TestData.orderRequest();
        String key = TestData.uniqueKey();
        int threads = 2;
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<OrderService.CreateOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return orders.createOrder(request, key);
                }));
            }
            ready.await();
            start.countDown();
            int created = 0;
            int replayed = 0;
            int duplicates = 0;
            for (Future<OrderService.CreateOutcome> future : futures) {
                try {
                    var outcome = future.get();
                    if (outcome.replayed()) {
                        replayed++;
                    } else {
                        created++;
                    }
                } catch (Exception e) {
                    assertThat(e.getCause()).isInstanceOf(DuplicateOrderException.class);
                    duplicates++;
                }
            }
            // Exactly one order, and the two callers split between one
            // create and one replay-or-duplicate. A plain retry afterwards
            // always returns the stored response.
            assertThat(created).isEqualTo(1);
            assertThat(replayed + duplicates).isEqualTo(1);
            assertThat(repository.findAll().stream()
                    .filter(o -> o.getClientReference().equals(request.clientReference()))
                    .count()).isEqualTo(1);
            var retry = orders.createOrder(request, key);
            assertThat(retry.replayed()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelMovesCreatedToCancelledAndCannotBeRepeated() {
        var created = orders.createOrder(TestData.orderRequest(), null).response();

        var cancelled = orders.cancelOrder(created.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");

        assertThatThrownBy(() -> orders.cancelOrder(created.id()))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void getMissingOrderIsNotFound() {
        assertThatThrownBy(() -> orders.getOrder(UUID.randomUUID()))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void listOrdersSupportsPagingAndStatusFilter() {
        String prefix = TestData.uniqueRef("PAGE");
        for (int i = 0; i < 3; i++) {
            orders.createOrder(TestData.orderRequest(prefix + "-" + i), null);
        }
        var page = orders.listOrders(null, PageRequest.of(0, 2));
        assertThat(page.content()).hasSize(2);
        assertThat(page.totalElements()).isGreaterThanOrEqualTo(3);

        var created = orders.listOrders(OrderStatus.CREATED, PageRequest.of(0, 100));
        assertThat(created.content())
                .extracting(r -> r.clientReference())
                .contains(prefix + "-0", prefix + "-1", prefix + "-2");
        var cancelled = orders.listOrders(OrderStatus.CANCELLED, PageRequest.of(0, 100));
        assertThat(cancelled.content())
                .extracting(r -> r.clientReference())
                .doesNotContain(prefix + "-0", prefix + "-1", prefix + "-2");
    }
}
