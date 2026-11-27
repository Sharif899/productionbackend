# Architecture

Short notes on the design decisions in production-backend and why each was made. The README describes what the service does; this file describes why it is built the way it is.

## Money in minor units

Amounts are `long` minor units (kobo for NGN), never floats and never `BigDecimal` in the API. Floats round; `BigDecimal` invites scale bugs across serialisation boundaries. A long of kobo is exact, sorts correctly, and maps cleanly to a `BIGINT` column with a `CHECK (amount > 0)`. The trade-off is that callers must send minor units, which the API documents and validates (`@Positive`).

## Flyway owns the schema; Hibernate only validates

`spring.jpa.hibernate.ddl-auto=validate`. The migrations in `db/migration` are the only thing that creates or changes tables, so the schema is reviewable, versioned, and identical in every environment. Hibernate validation catches a mapping that disagrees with the migrated schema at startup (it caught two real mismatches during development: a reserved-word column name and a derived query over an association). `validate` also means the app cannot silently fix a production schema, which is the point.

## Idempotency: stored responses, scoped keys, one transaction

A key is stored as `(scope, key)` in `idempotency_keys`, where scope is the endpoint (`create-order`, `process-payment`), so the same key on a different endpoint is a different request. Each row stores the SHA-256 of the canonical request JSON (properties and map entries sorted, so field order does not matter) plus the response body and status. The row is written in the same transaction as the order or payment it records, so a stored response always has the real resource behind it.

The store runs in its own transaction (`REQUIRES_NEW`) and flushes immediately. That keeps a failed key insert from poisoning the caller's transaction: the caller catches the constraint violation, looks the key up again in its own healthy transaction, and turns it into a replay (same payload) or a 422 (different payload). A concurrent replay can still surface as a 409 duplicate on the business key instead of a replay, because the database constraint on the reference fires first; a plain retry then returns the stored response. That trade-off is accepted and tested.

Two subtleties found while building this:

1. **Persist, not save.** `IdempotencyService.store` uses `EntityManager.persist`, not the repository's `save`. The record's id is assigned in its constructor and the entity has no `@Version`, so Spring Data's `isNew` check says "not new" and `save` would `merge` instead of inserting. A merge of a duplicate key silently overwrites the stored response instead of violating the primary key, which would break the callers' race handling. The unit test for a duplicate store originally passed for the wrong reason (a null-timestamp NOT NULL violation); `persist` makes it fail on the primary key, as intended. The flush failure is translated to `DataIntegrityViolationException` only when the cause chain shows a constraint violation (Hibernate `ConstraintViolationException` or SQLState `23xxx`); other failures propagate as they are.
2. **The column is `idempotency_key`, not `key`.** `KEY` is a reserved word in SQL and in H2. The migration creates `idempotency_key`, and the entity maps its `key` field to that column with `@AttributeOverride` on the `@EmbeddedId`, so the Java field name stays readable while the schema stays valid.

## Duplicate detection: two layers

The service checks for the reference first (fast path, and it can return the existing order id for the 409), and the database enforces it with a unique constraint. The check alone would race; the constraint alone would give a poor error. Together, a concurrent duplicate is a 409 with the existing order identified, and exactly one row survives. The same pattern applies to payment references and to the one-payment-per-order rule (unique on `payments.order_id`).

## Rate limiting: token bucket, in memory

One bucket per API key (or per client address when no key is sent), refilling continuously at the configured rate and holding at most one minute's budget, so a client can burst up to the full budget and then settles to the steady rate. The clock is injected so tests move time forward deterministically instead of sleeping. Buckets live in memory: right for a single instance, and the filter and limiter are small enough to move to Redis (one bucket per key, same refill math) when the service runs on more than one instance. Health and metrics are exempt from limiting.

## Caching: memory by default, Redis when asked

`GET /api/v1/orders/{id}` is `@Cacheable`; cancel and payment evict the entry, so a read after a mutation never serves a stale status. The default cache is an in-process map, so the service works with nothing else running. Setting `app.cache.type=redis` switches to a `RedisCacheManager` with JSON values and a 10 minute TTL. Redis is a cache, not a dependency, so `management.health.redis.enabled=false`: an absent Redis must not mark the service unhealthy. The Redis path is configured and code-reviewed but has not been run live (no Redis is available in the build environment); see STATUS.md.

## Connection pool: 20, because a create holds two

A create holds two connections at once: the order transaction and the `REQUIRES_NEW` idempotency store. The first load test, with a pool of 10 and 10 concurrent workers, measured 51.2 req/s with 21 requests timing out at the 10 s client timeout, all of them creates waiting for a connection. Raising the pool to 20 removed the timeouts entirely and throughput went to 176.2 req/s on the same hardware. The pool stays small on purpose (free-tier databases limit connections), but it must cover concurrency times connections-per-request, and 20 does that for the documented load shape. The postgres profile uses the same size.

## Timestamps belong in the constructor

`@PrePersist` fires when Hibernate flushes, which is after the service has already mapped the response. The first live run returned `createdAt: null` on creates and payments, while a later GET returned the timestamps. The fix is to set `createdAt`/`updatedAt` in the entity constructors, with `@PrePersist` kept as a backstop that only fills a null. Entities should be fully initialised when constructed, not only after a flush.

## Configuration binding: grouped records, not flat fields

`AppProperties` is a record with nested `RateLimit` and `Cache` records, so the properties read as `app.rate-limit.requests-per-minute` and `app.cache.type`. A flat record would not bind those dotted names: `app.rate-limit.requests-per-minute` is two levels, not one kebab-case field name, and the values would silently stay at their defaults (which happened during development: the rate limit test exposed that the configured 5/minute was being ignored and the default 100 was in force). The compact constructor fills in a group that has no properties at all.

## PostgreSQL profile: fail fast, no silent fallback

`application-postgres.properties` declares the URL, username and password as placeholders with no default. In a real Boot run, an unset `SPRING_DATASOURCE_URL` fails the context at startup instead of silently falling back to H2. The profile test proves this with a real `SpringApplicationBuilder` run, because `ApplicationContextRunner` passes property values through literally (a `${...}` placeholder reaches the DataSource unresolved and nothing connects when Flyway and validation are off), so it cannot test the mechanism the profile relies on. The dialect is set explicitly in the profile, so startup does not need a connection to determine it.

## H2 identifiers are case-sensitive when quoted

Flyway quotes its history table name and columns in lowercase (`"flyway_schema_history"`, `"success"`). H2 uppercases unquoted identifiers, so `SELECT ... FROM flyway_schema_history WHERE success = TRUE` fails with "table not found" and then "column not found". The smoke test quotes both identifiers. The migration-created tables are unquoted (uppercased by H2), which is why the same test's queries against `orders` and `payments` work unquoted. This only affects H2; PostgreSQL lowercases unquoted identifiers, so the same SQL works there.

## Dockerfile: runtime-only image, no RUN steps

The image copies a jar built by Maven first (locally `mvn package`, in CI the workflow's Maven step) into `eclipse-temurin:21-jre-alpine`, runs as UID 10001 (no user needs to exist in `/etc/passwd` for the kernel to run as it), and healthchecks with `wget` against `/actuator/health`. There is deliberately no `RUN` instruction: the environment this image is verified in cannot execute containers, so a `RUN` step would make `docker build` fail there. Where container execution works, a multi-stage build (Maven stage plus this runtime stage) is the natural next step and the CI workflow already builds the jar before the image. The image is not verified locally here: no Docker binary exists on this VM (see STATUS.md); CI runs `docker build` and `docker compose config -q`.

## What is deliberately not here

- **No authentication.** The API key in the rate limiter is a client identifier, not a credential. Real auth (API key verification or OAuth2) is a separate concern and would be added before exposing this beyond a trusted network.
- **No external payment gateway.** Processing a payment records it in the ledger. A gateway integration would add a provider client, webhook handling, and reconciliation, and would change the idempotency story (the gateway's own idempotency key).
- **Single-instance state.** Rate-limit buckets and the default cache live in memory. The Redis cache option covers reads across instances; the buckets would need the same treatment.
- **`PaymentStatus.FAILED` is ledger-only.** It exists for a settlement or chargeback flow to write. The API never produces it; a decline is a 4xx error.
