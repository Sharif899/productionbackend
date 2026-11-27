package com.sharif899.prodbackend.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharif899.prodbackend.TestData;
import com.sharif899.prodbackend.repository.OrderRepository;
import com.sharif899.prodbackend.web.dto.CreateOrderRequest;
import com.sharif899.prodbackend.web.dto.PaymentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orders;

    private String createOrderJson(CreateOrderRequest request) throws Exception {
        return mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(header().exists("X-Request-Id"))
                .andReturn().getResponse().getContentAsString();
    }

    private String orderIdFrom(String createResponseJson) throws Exception {
        return objectMapper.readTree(createResponseJson).path("data").path("id").asText();
    }

    @Test
    void createOrderReturns201WithTheOrder() throws Exception {
        var request = TestData.orderRequest();
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.clientReference").value(request.clientReference()))
                .andExpect(jsonPath("$.data.currency").value("NGN"))
                .andExpect(jsonPath("$.data.totalAmountMinor").value(250_000))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void replayReturns200WithTheSameOrderAndNoSecondRow() throws Exception {
        var request = TestData.orderRequest();
        String key = TestData.uniqueKey();
        String body = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(status().isCreated());

        String replayed = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();

        String firstId = orderIdFrom(createOrderJson(TestData.orderRequest())); // unrelated order, sanity
        assertThat(orderIdFrom(replayed)).isNotEqualTo(firstId);
        assertThat(orders.findByClientReference(request.clientReference())).isPresent();
        assertThat(orders.findAll().stream()
                .filter(o -> o.getClientReference().equals(request.clientReference()))
                .count()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentPayloadIs422() throws Exception {
        String key = TestData.uniqueKey();
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(objectMapper.writeValueAsString(TestData.orderRequest())))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(objectMapper.writeValueAsString(TestData.orderRequest())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void duplicateReferenceIs409WithTheExistingOrderId() throws Exception {
        var request = TestData.orderRequest();
        String first = createOrderJson(request);
        String firstId = orderIdFrom(first);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_ORDER"))
                .andExpect(jsonPath("$.error.fieldErrors.existingOrderId").value(firstId));
    }

    @Test
    void validationErrorsAre400WithFieldErrors() throws Exception {
        var bad = new CreateOrderRequest("", "", "ngn", 0);
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors.clientReference").exists())
                .andExpect(jsonPath("$.error.fieldErrors.customerName").exists())
                .andExpect(jsonPath("$.error.fieldErrors.currency").exists())
                .andExpect(jsonPath("$.error.fieldErrors.totalAmountMinor").exists());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    @Test
    void getMissingOrderIs404() throws Exception {
        mockMvc.perform(get("/api/v1/orders/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void getWithAnInvalidUuidIs400() throws Exception {
        mockMvc.perform(get("/api/v1/orders/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    void listOrdersPagesAndFilters() throws Exception {
        String prefix = TestData.uniqueRef("LIST");
        for (int i = 0; i < 3; i++) {
            createOrderJson(TestData.orderRequest(prefix + "-" + i));
        }
        mockMvc.perform(get("/api/v1/orders")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
        mockMvc.perform(get("/api/v1/orders")
                        .param("status", "CANCELLED")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].clientReference",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(containsString(prefix)))));
    }

    @Test
    void cancelFlow() throws Exception {
        String id = orderIdFrom(createOrderJson(TestData.orderRequest()));

        mockMvc.perform(post("/api/v1/orders/" + id + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        mockMvc.perform(post("/api/v1/orders/" + id + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        mockMvc.perform(post("/api/v1/orders/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TestData.paymentRequest(250_000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void paymentFlow() throws Exception {
        String id = orderIdFrom(createOrderJson(TestData.orderRequest()));
        var payment = TestData.paymentRequest(250_000L);

        String paymentJson = mockMvc.perform(post("/api/v1/orders/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payment)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.orderId").value(id))
                .andReturn().getResponse().getContentAsString();
        String paymentId = objectMapper.readTree(paymentJson).path("data").path("id").asText();
        assertThat(paymentId).isNotBlank();

        mockMvc.perform(get("/api/v1/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        mockMvc.perform(get("/api/v1/orders/" + id + "/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(paymentId));

        // A second payment can never charge twice.
        mockMvc.perform(post("/api/v1/orders/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TestData.paymentRequest(250_000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void wrongPaymentAmountIs422() throws Exception {
        String id = orderIdFrom(createOrderJson(TestData.orderRequest()));

        mockMvc.perform(post("/api/v1/orders/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TestData.paymentRequest(100L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.error.fieldErrors.expectedAmountMinor").value("250000"));
    }

    @Test
    void paymentForAMissingOrderIs404() throws Exception {
        mockMvc.perform(post("/api/v1/orders/" + UUID.randomUUID() + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TestData.paymentRequest(250_000L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
