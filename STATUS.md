# STATUS

## Tests

52 tests, 0 failures, 0 errors, 0 skipped (`mvn test`, 2026-11-27).

- SmokeTest (1): context loads, both Flyway migrations ran.
- IdempotencyServiceTest (8): hash determinism and field-order independence, store/lookup, duplicate store fails on the primary key, same key in another scope is independent, JSON round trip.
- OrderServiceTest (9): create, replay with the same key and payload, same key with a different payload is a 422, duplicate reference is a 409 with the existing order id, concurrent creates with the same reference leave exactly one order, concurrent replays with the same key leave exactly one order, cancel and double-cancel, missing order is 404, paging and status filter.
- PaymentServiceTest (9): full payment, wrong amount is a 422 with no payment row, paying a paid or cancelled order is a 409, duplicate payment reference is a 409, replay, same key with a different payload is a 422, missing order is 404, concurrent payments on one order leave exactly one payment.
- RateLimiterTest (6): token bucket refill, burst, retry-after, disabled limiter, per-key independence.
- OrderControllerTest (13): full HTTP matrix (201 create, 200 replay, 422 conflict, 409 duplicate, 400 validation and malformed JSON, 404, invalid UUID, list paging/filter, cancel flow, payment flow, wrong amount 422, payment for a missing order 404).
- RateLimitFilterTest (3): 5/minute limit gives 429 with `Retry-After` and `X-RateLimit` headers on the 6th request, a different key has its own budget, health is not rate limited.
- CacheTest (1): get populates the cache, cancel evicts it, the next get re-caches the cancelled state.
- PostgresProfileTest (2): the postgres profile fails fast with no `SPRING_DATASOURCE_URL` set; with a URL set it binds a Hikari datasource from the environment.

## Load test (measured 2026-11-27)

Method: `scripts/load_test.py --duration 30 --concurrency 10` against the packaged jar (`java -jar target/production-backend-1.0.0.jar`) running on this VM (1.5 shared CPUs, H2 in-memory database), each worker with its own API key, mixed workload of create (fresh idempotency key each time) then GET by id, plus a list every 10th iteration. Rate limit raised for the measurement with `APP_RATE_LIMIT_REQUESTS_PER_MINUTE=100000`, so the numbers are the app's, not the limiter's. Full JSON in `scripts/load_test_result.json`.

- Total: 5,301 requests in 30 seconds.
- Throughput: 176.2 req/s (176.7 req/s over the 30 s window).
- Latency: p50 49.7 ms, p95 111.6 ms, p99 188.2 ms, max 1,823 ms.
- Errors: 0 (2,775 x 200, 2,526 x 201, no timeouts, no 429s).
- By endpoint: create 84.0 req/s (p50 80.6 ms, p95 165.4 ms); get 84.0 req/s (p50 15.8 ms, p95 87.3 ms); list 8.3 req/s (p50 77.6 ms, p95 113.4 ms).

A first run with the Hikari pool at 10 measured 51.2 req/s with 21 requests timing out at the 10 s client timeout (a create holds two connections at once, so 10 workers queued for a connection). Raising the pool to 20 removed the timeouts entirely. The shipped configuration is the 20-connection one.

## Live verification (2026-11-27)

The packaged jar was run with `java -jar` against H2 and exercised with curl: health `{"status":"UP"}`, create 201 with timestamps, replay 200, duplicate reference 409, get, payment 201 SUCCEEDED, list, metrics endpoint responding. All of it worked. The jar is stopped now; nothing is left running.

## Known limitations

- **Docker could not be verified locally.** No Docker binary or socket exists on this VM (`docker: command not found`, no `podman`/`nerdctl`/`containerd` either), despite the brief saying Docker 29.x is installed. The Dockerfile and `docker-compose.yml` were checked as far as possible without it: the compose file parses as YAML with the expected services and `depends_on` health conditions, and the Dockerfile was checked instruction by instruction (no `RUN`, non-root `USER 10001`, `HEALTHCHECK`, jar `COPY`). The CI workflow runs `docker build` and `docker compose config -q`, so the containerised run is verified there, not here. If CI fails on the image, that is the first place to look.
- **No live PostgreSQL run.** There is no PostgreSQL server in this environment. The postgres profile is verified by configuration tests only (fail-fast without a URL, datasource binding with a URL set). The Flyway migrations are written for PostgreSQL (V1/V2 use standard SQL that runs on both), and H2 runs in PostgreSQL mode, but a real Postgres run has not happened yet.
- **No live Redis run.** Same reason. The Redis cache path (`app.cache.type=redis`) is configured and code-reviewed, but only the in-memory cache has been exercised at runtime.
- **Load numbers are H2 on a small shared VM.** 1.5 shared CPUs, in-memory database, single instance. They show the app is not the bottleneck at this scale; they are not production PostgreSQL numbers.
- **Single-instance state.** Rate-limit buckets and the default cache live in memory. The Redis cache option covers reads across instances; the buckets would need the same treatment for a multi-instance deployment.
- **No authentication.** The `X-Api-Key` header is a rate-limiting client identifier, not a credential.
