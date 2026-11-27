package com.sharif899.prodbackend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sharif899.prodbackend.domain.IdempotencyKeyId;
import com.sharif899.prodbackend.domain.IdempotencyRecord;
import com.sharif899.prodbackend.repository.IdempotencyRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Idempotency keys, the way Stripe documents them: a client sends an
 * {@code Idempotency-Key} header with a mutating request. The first
 * request is processed and its response is stored under the key; a
 * retry with the same key and the same payload returns the stored
 * response instead of processing again, so a retry can never create
 * a second order or a second charge. The same key with a different
 * payload is a 422, never a silent replay.
 *
 * <p>One rule makes this safe: a stored response is written in the
 * same transaction as the resource it records (the service calls
 * {@link #store} last, and the row commits together with the order
 * or payment), so a stored response always has the real resource
 * behind it.
 *
 * <p>{@link #store} runs in its own transaction
 * ({@code REQUIRES_NEW}) and flushes immediately. That keeps a
 * failed key insert from poisoning the caller's transaction: the
 * caller catches the constraint violation, looks the key up again
 * in its own healthy transaction, and turns it into a replay or a
 * 422. A concurrent replay of the same key and payload can still
 * surface as a 409 duplicate on the business key instead of a
 * replay (the database constraint fires first); a plain retry then
 * returns the stored response. That trade-off is documented in
 * docs/ARCHITECTURE.md.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public IdempotencyService(IdempotencyRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * SHA-256 of the canonical JSON of the request. Canonical means
     * sorted property names and sorted map entries, so two equal
     * payloads hash the same no matter how the client ordered the
     * fields, and any different payload hashes differently.
     */
    @SuppressWarnings("deprecation") // SORT_PROPERTIES_ALPHABETICALLY is deprecated but stable
    public String hash(Object request) {
        try {
            ObjectMapper canonical = objectMapper.copy()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
            byte[] json = canonical.writeValueAsBytes(request);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not hash idempotency request", e);
        }
    }

    /** The stored record for a key, if this key was used before. */
    public Optional<IdempotencyRecord> lookup(String scope, String key) {
        return repository.findById(new IdempotencyKeyId(scope, key));
    }

    /**
     * Store a response under a key, in its own transaction, flushed
     * immediately. Throws {@link DataIntegrityViolationException} if
     * the key is already taken; the caller decides replay vs
     * conflict by looking the key up again.
     *
     * <p>This uses {@code EntityManager.persist} on purpose, not
     * the repository's {@code save}: a record's id is assigned in
     * its constructor and the entity has no {@code @Version}, so
     * Spring Data's {@code isNew} check says "not new" and
     * {@code save} would {@code merge} instead of inserting. A
     * merge of a duplicate key silently overwrites the stored
     * response instead of violating the primary key, which would
     * break the callers' race handling. {@code persist} always
     * inserts, so a taken key fails on the flush, as it must.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void store(String scope, String key, String requestHash, String responseBody, int statusCode) {
        try {
            entityManager.persist(new IdempotencyRecord(
                    new IdempotencyKeyId(scope, key), requestHash, responseBody, statusCode));
            entityManager.flush();
        } catch (PersistenceException e) {
            if (isConstraintViolation(e)) {
                throw new DataIntegrityViolationException(
                        "Idempotency key already stored: " + scope + "/" + key, e);
            }
            throw e;
        }
    }

    /** True when the failure is a database constraint violation (a taken key), not a connection error. */
    private static boolean isConstraintViolation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException) {
                return true;
            }
            if (current instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }

    /** Serialise a response DTO to the JSON stored under a key. */
    public String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise idempotency response", e);
        }
    }

    /** Deserialise a stored response body back to its DTO. */
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json.getBytes(StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new IllegalStateException("Could not deserialise stored idempotency response", e);
        }
    }
}
