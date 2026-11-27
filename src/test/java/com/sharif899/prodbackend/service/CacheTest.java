package com.sharif899.prodbackend.service;

import com.sharif899.prodbackend.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The read cache: a lookup is cached, a mutation evicts it. */
@SpringBootTest
class CacheTest {

    @Autowired
    private OrderService orders;

    @Autowired
    private CacheManager cacheManager;

    @Test
    void getOrderPopulatesTheCacheAndCancelEvictsIt() {
        UUID id = orders.createOrder(TestData.orderRequest(), null).response().id();

        var first = orders.getOrder(id);
        assertThat(first.status()).isEqualTo("CREATED");
        var cached = cacheManager.getCache("orders");
        assertThat(cached).isNotNull();
        assertThat(cached.get(id)).isNotNull();

        var second = orders.getOrder(id);
        assertThat(second).isEqualTo(first);

        orders.cancelOrder(id);
        assertThat(cached.get(id)).isNull();

        var afterCancel = orders.getOrder(id);
        assertThat(afterCancel.status()).isEqualTo("CANCELLED");
        assertThat(cached.get(id)).isNotNull();
    }
}
