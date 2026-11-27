# production-backend

A production-grade order and payment processing API in Java 21 and Spring Boot 3.3.5. It exists to show senior software engineering, not to demo an idea: every endpoint works, every guarantee below is covered by tests, and the numbers in this file were measured, not estimated.

## What it does

- **Orders**: create, get, list (paged, filterable by status), cancel.
- **Payments**: process a full payment for an order, list an order's payments.
- **Idempotency**: a repeated request with the same `Idempotency-Key` and the same payload returns the stored response and does not re-process. A different payload with the same key is a 422.
- **Duplicate detection**: a client order reference is unique. A duplicate returns 409 with the existing order's id, never a second row, including under concurrent requests.
- **Rate limiting**: token bucket per API key (or client address), 100 requests/minute by default. Over the limit is 429 with a `Retry-After` header.
- **Validation and errors**: every response uses one envelope, `{success, data, error}`, and every error carries a machine-readable `code` plus field errors where they apply.
- **Persistence**: JPA/Hibernate with Flyway migrations. H2 in-memory by default; PostgreSQL via environment variables.
- **Caching**: order lookups are cached. In-process by default; Redis when configured, JSON values, 10 minute TTL.
- **Observability**: Actuator health/info/metrics, structured request logging with a request id on every line.

## Quick start

Requires Java 21 and Maven 3.9+.

```bash
mvn test
mvn package -DskipTests
java -jar target/production-backend-1.0.0.jar
```

The API listens on `http://localhost:8080`. No database setup is needed: it runs on an in-memory H2 database with the schema migrated by Flyway at startup.

With Docker Compose (api + PostgreSQL 16 + Redis 7):

```bash
cp .env.example .env   # set POSTGRES_PASSWORD
mvn package -DskipTests   # the image copies the built jar in
docker compose up --build
```

With PostgreSQL directly (no Compose), set the connection in the environment and activate the profile:

```bash
export SPRING_PROFILES_ACTIVE=postgres
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/proddb
export SPRING_DATASOURCE_USERNAME=produser
export SPRING_DATASOURCE_PASSWORD=change-me
java -jar target/production-backend-1.0.0.jar
```

The postgres profile fails fast at startup if `SPRING_DATASOURCE_URL` is not set, instead of silently falling back to H2. No credential is stored in the repo.

## API

Base path `/api/v1/orders`. All responses use the envelope `{"success": true, "data": {...}, "error": null}` on success and `{"success": false, "data": null, "error": {"code", "message", "fieldErrors"}}` on failure. Every response carries an `X-Request-Id` header; a replayed idempotent response also carries `X-Idempotency-Replayed: true`.

| Method | Path | Description | Success |
| --- | --- | --- | --- |
| POST | `/api/v1/orders` | Create an order. Optional `Idempotency-Key` header. | 201, 200 on replay |
| GET | `/api/v1/orders/{id}` | Get one order (cached). | 200 |
| GET | `/api/v1/orders?page=0&size=20&status=CREATED` | List orders, paged, optional status filter. | 200 |
| POST | `/api/v1/orders/{id}/cancel` | Cancel a CREATED order. | 200 |
| POST | `/api/v1/orders/{id}/payments` | Process a payment. Optional `Idempotency-Key` header. | 201, 200 on replay |
| GET | `/api/v1/orders/{id}/payments` | List an order's payments. | 200 |
| GET | `/actuator/health` | Health. UP only when the database is reachable. | 200 |
| GET | `/actuator/info` | Build info. | 200 |
| GET | `/actuator/metrics` | Metrics, including `http.server.requests`. | 200 |

### Create an order

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000' \
  -d '{"clientReference":"ORD-1001","customerName":"Ada Lovelace","currency":"NGN","totalAmountMinor":250000}'
```

Money is in minor units (kobo for NGN), as a long, never a float. `currency` must be a 3-letter ISO 4217 code. `clientReference` (max 64 chars) and `customerName` (max 200) are required, `totalAmountMinor` must be positive.

### Process a payment

```bash
curl -X POST http://localhost:8080/api/v1/orders/<order-id>/payments \
  -H 'Content-Type: application/json' \
  -d '{"reference":"PAY-1001","method":"CARD","amountMinor":250000}'
```

A payment must be for the order's full amount and is the only payment an order can have. A partial amount is 422, a second payment is 409 (the order is already PAID), a payment on a cancelled order is 409. `method` is one of `CARD`, `BANK_TRANSFER`, `WALLET`, `CASH`. There is no external payment gateway here: processing a payment records it in the ledger and moves the order to PAID. A decline is a 4xx error, not a FAILED row; `PaymentStatus.FAILED` is a ledger status for a settlement or chargeback flow, not something this API produces.

### Error codes

| Code | HTTP | Meaning |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | A request field failed validation; `fieldErrors` names each field. |
| `MALFORMED_JSON` | 400 | The request body is not valid JSON. |
| `INVALID_PARAMETER` | 400 | A path or query parameter is invalid (for example a bad UUID). |
| `NOT_FOUND` | 404 | The order does not exist. |
| `DUPLICATE_ORDER` | 409 | `clientReference` is already taken; `fieldErrors.existingOrderId` holds the existing order. |
| `DUPLICATE_PAYMENT` | 409 | The payment `reference` is already taken. |
| `INVALID_STATE` | 409 | The order is in a state that forbids the operation (already paid, already cancelled). |
| `RATE_LIMITED` | 429 | Over the per-key rate limit; see `Retry-After`. |
| `IDEMPOTENCY_CONFLICT` | 422 | The same `Idempotency-Key` was used with a different payload. |
| `PAYMENT_AMOUNT_MISMATCH` | 422 | The payment amount is not the order total; `fieldErrors` gives expected and actual. |
| `INTERNAL_ERROR` | 500 | Anything unexpected. |

## How the guarantees behave

**Idempotency.** The key is scoped per endpoint (`create-order`, `process-payment`), so the same key on a different endpoint is a different request. The service stores the SHA-256 of the canonical request JSON (sorted properties and map entries, so field order does not matter) plus the response body and status, in the same transaction as the order or payment. A retry with the same key and payload returns the stored response with HTTP 200 and `X-Idempotency-Replayed: true`, and creates nothing. A retry with a different payload is 422. A concurrent replay that loses the race on the key is turned into a replay by re-reading the stored record; a plain retry after that always returns the stored response.

**Duplicates.** The check is two layers: the service looks the reference up first (fast path, and it can return the existing order id), and the database enforces it with a unique constraint. A concurrent duplicate that passes the check hits the constraint and is reported as 409. Exactly one row survives.

**Rate limiting.** In-memory token bucket, one bucket per `X-Api-Key` header, or per client address when no key is sent. Each bucket refills continuously and holds at most one minute's budget, so a client can burst up to the full budget and then settles to the steady rate. Every API response carries `X-RateLimit-Limit` and `X-RateLimit-Remaining`. Health and metrics are not rate limited. Buckets live in memory, which is the right call for a single instance; a multi-instance deployment would move them to Redis.

**Caching.** `GET /api/v1/orders/{id}` is cached for 10 minutes under `app.cache.type=redis`, and in-process under the default `memory`. Mutations evict the entry, so a read after a cancel or a payment never serves a stale status. Redis is optional: with the default `memory` cache the app starts and works with no Redis available, and the Actuator health endpoint does not report Redis down (Redis is a cache, not a dependency).

## Configuration

| Setting | Environment variable | Default | Notes |
| --- | --- | --- | --- |
| `spring.profiles.active` | `SPRING_PROFILES_ACTIVE` | none | `postgres` for PostgreSQL |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | H2 in-memory | Required (no default) with the postgres profile |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | `sa` (H2) | Required with the postgres profile |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | empty (H2) | Required with the postgres profile |
| `app.cache.type` | `CACHE_TYPE` | `memory` | `memory` or `redis` |
| `spring.data.redis.host` | `REDIS_HOST` | `localhost` | Only used when `app.cache.type=redis` |
| `spring.data.redis.port` | `REDIS_PORT` | `6379` | Only used when `app.cache.type=redis` |
| `app.rate-limit.enabled` | none | `true` | Set `app.rate-limit.enabled=false` to disable |
| `app.rate-limit.requests-per-minute` | `APP_RATE_LIMIT_REQUESTS_PER_MINUTE` | `100` | Per API key, per minute |

## Tests

52 tests, all passing: unit tests for the rate limiter and the idempotency hash/store/lookup, service tests for orders and payments (including concurrent duplicate creates, concurrent replays of one key, and concurrent payments on one order), MockMvc tests for the full HTTP matrix (validation, 404/409/422, replay, cancel and payment flows), a rate-limit filter test with a 5/minute limit, a cache test, a Postgres profile test, and a smoke test that the context loads and both Flyway migrations ran.

```bash
mvn test
```

Note for this sandbox: Java has no network here, so the local build fetches dependencies through Python first, then runs Maven offline:

```bash
python3 ~/workspace/tools/mvnfetch.py pom.xml
/opt/maven/current/bin/mvn -o -B -Dmaven.repo.local=/home/hatch/.m2/repository test
```

On GitHub Actions the CI workflow runs plain `mvn -B test` (the runner has network).

## Load test

Measured with the script in `scripts/load_test.py` against the packaged jar running on this VM (1.5 shared CPUs, H2 in-memory database), 10 concurrent workers for 30 seconds, each worker using its own API key, mixed workload: create an order (with a fresh idempotency key), then GET it, plus a list every 10th iteration. The rate limit was raised for the measurement (`APP_RATE_LIMIT_REQUESTS_PER_MINUTE=100000`) so the numbers are the app's, not the limiter's. Reproduce it with:

```bash
APP_RATE_LIMIT_REQUESTS_PER_MINUTE=100000 java -jar target/production-backend-1.0.0.jar
python3 scripts/load_test.py --duration 30 --concurrency 10
```

| | |
| --- | --- |
| Total requests | 5,301 in 30 seconds |
| Throughput | **176.2 req/s** (176.7 req/s over the 30 s window) |
| p50 latency | 49.7 ms |
| p95 latency | 111.6 ms |
| p99 latency | 188.2 ms |
| Max latency | 1,823 ms |
| Errors | 0 (2,775 × 200, 2,526 × 201, no timeouts, no 429s) |

By endpoint: create 84.0 req/s, p50 80.6 ms, p95 165.4 ms; get 84.0 req/s, p50 15.8 ms, p95 87.3 ms; list 8.3 req/s, p50 77.6 ms, p95 113.4 ms. The full JSON result is in `scripts/load_test_result.json`.

The first run, before a fix, measured 51.2 req/s with 21 requests timing out at the 10 s client timeout: the Hikari pool was 10 connections and a create holds two at once (the order transaction plus the REQUIRES_NEW idempotency store), so 10 workers queued for a connection. Raising the pool to 20 removed the timeouts entirely and the throughput went to 176.2 req/s. See `docs/ARCHITECTURE.md` for the reasoning.

## Project layout

```
src/main/java/com/sharif899/prodbackend/
  ProductionBackendApplication.java   entry point
  config/    AppProperties, AppConfig (Clock), CacheConfig
  domain/    Order, Payment, IdempotencyRecord (+ composite key), enums
  repository/  Spring Data JPA repositories
  service/   OrderService, PaymentService, IdempotencyService, RateLimiter
  web/       OrderController, GlobalExceptionHandler, RequestIdFilter, RateLimitFilter
  web/dto/   ApiResponse envelope, request and response DTOs
src/main/resources/
  application.properties            H2 default, env-driven
  application-postgres.properties   PostgreSQL profile, fails fast without a URL
  db/migration/  V1 orders+payments, V2 idempotency_keys
src/test/java/...                    52 tests
scripts/load_test.py                 reproducible load test (stdlib only)
Dockerfile, docker-compose.yml, .env.example
.github/workflows/ci.yml            Java 21, tests, package, docker build, compose validation
docs/ARCHITECTURE.md                design decisions and why
STATUS.md                           test count, load numbers, known limitations
```

## Licence

MIT. See `LICENSE`.
