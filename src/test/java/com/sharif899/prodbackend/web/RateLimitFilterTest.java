package com.sharif899.prodbackend.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rate limit filter, through HTTP. This class runs with a tiny
 * limit (5 requests a minute) and its own database, so it gets a
 * fresh application context and cannot disturb the other tests.
 */
@SpringBootTest(properties = {
        "app.rate-limit.requests-per-minute=5",
        "spring.datasource.url=jdbc:h2:mem:proddb_ratelimit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
class RateLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sixthRequestFromTheSameKeyIs429WithRetryAfter() throws Exception {
        String apiKey = "test-key-" + System.nanoTime();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/orders").header("X-Api-Key", apiKey))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "5"));
        }
        mockMvc.perform(get("/api/v1/orders").header("X-Api-Key", apiKey))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void aDifferentKeyStillHasItsOwnBudget() throws Exception {
        String exhausted = "test-key-exhausted-" + System.nanoTime();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/orders").header("X-Api-Key", exhausted))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/orders").header("X-Api-Key", exhausted))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/v1/orders").header("X-Api-Key", "test-key-fresh-" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "4"));
    }

    @Test
    void healthIsNotRateLimited() throws Exception {
        for (int i = 0; i < 8; i++) {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk());
        }
    }
}
