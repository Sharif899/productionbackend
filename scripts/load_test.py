#!/usr/bin/env python3
"""Load test for production-backend.

Hammers the real API (create order, get order, list orders) with N
concurrent workers for a fixed duration and reports real throughput
(requests/second) and latency percentiles. Standard library only,
so it runs anywhere Python 3.9+ is installed.

The API rate-limits per API key (100/min by default), so each worker
uses its own key. Even so, measure against a server started with
the limit raised, otherwise the numbers are the limiter's, not the
app's:

    APP_RATE_LIMIT_REQUESTS_PER_MINUTE=100000 java -jar target/production-backend-1.0.0.jar
    python3 scripts/load_test.py --duration 30 --concurrency 10

Usage:
    python3 scripts/load_test.py [--base-url URL] [--duration S]
                                 [--concurrency N] [--output FILE]
"""

import argparse
import json
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

HEALTH_PATH = "/actuator/health"


def percentile(values, pct):
    """Nearest-rank percentile of a sorted list."""
    if not values:
        return None
    index = min(len(values) - 1, max(0, int(pct / 100 * len(values)) - 1))
    return values[index]


def wait_for_health(base_url, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(base_url + HEALTH_PATH, timeout=5) as response:
                if response.status == 200:
                    body = json.loads(response.read())
                    if body.get("status") == "UP":
                        return True
        except Exception:
            pass
        time.sleep(1)
    return False


class Worker(threading.Thread):
    def __init__(self, worker_id, base_url, stop_at, results, lock):
        super().__init__(daemon=True)
        self.worker_id = worker_id
        self.base_url = base_url
        self.stop_at = stop_at
        self.results = results
        self.lock = lock
        self.api_key = "load-test-%d" % worker_id

    def record(self, endpoint, status, latency_ms, error):
        with self.lock:
            self.results.append((endpoint, status, latency_ms, error))

    def run(self):
        iteration = 0
        while time.time() < self.stop_at:
            iteration += 1
            reference = "LOAD-%s" % uuid.uuid4().hex[:16]
            body = {
                "clientReference": reference,
                "customerName": "Load Test",
                "currency": "NGN",
                "totalAmountMinor": 250000,
            }
            headers = {"Idempotency-Key": uuid.uuid4().hex}
            # Create order (with its own idempotency key per request).
            result, latency_ms, error = self._request(
                "POST", "/api/v1/orders", body, headers)
            self.record("create", self._status(result), latency_ms, error)
            order_id = self._order_id(result)
            if order_id is not None:
                result, latency_ms, error = self._request(
                    "GET", "/api/v1/orders/" + order_id, None, {})
                self.record("get", self._status(result), latency_ms, error)
            if iteration % 10 == 0:
                result, latency_ms, error = self._request(
                    "GET", "/api/v1/orders?page=0&size=20", None, {})
                self.record("list", self._status(result), latency_ms, error)

    def _request(self, method, path, body, extra_headers):
        url = self.base_url + path
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("X-Api-Key", self.api_key)
        for name, value in extra_headers.items():
            req.add_header(name, value)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                payload = response.read()
                status = response.status
        except urllib.error.HTTPError as error:
            payload = error.read()
            status = error.code
        except Exception as error:
            return None, (time.perf_counter() - started) * 1000, type(error).__name__
        latency_ms = (time.perf_counter() - started) * 1000
        return (status, payload), latency_ms, None

    @staticmethod
    def _status(result):
        return result[0] if result is not None else 0

    @staticmethod
    def _order_id(result):
        if result is None:
            return None
        status, payload = result
        if status not in (200, 201):
            return None
        try:
            return json.loads(payload)["data"]["id"]
        except (KeyError, TypeError, ValueError):
            return None


def summarise(results, elapsed_s):
    total = len(results)
    by_endpoint = {}
    for endpoint, status, latency_ms, error in results:
        by_endpoint.setdefault(endpoint, []).append((status, latency_ms, error))
    latencies = sorted(latency for _, _, latency, _ in results if latency is not None)
    statuses = {}
    errors = {}
    for _, status, _, error in results:
        if error:
            errors[error] = errors.get(error, 0) + 1
        else:
            statuses[status] = statuses.get(status, 0) + 1
    summary = {
        "total_requests": total,
        "elapsed_seconds": round(elapsed_s, 2),
        "requests_per_second": round(total / elapsed_s, 1) if elapsed_s else 0,
        "latency_ms": {
            "p50": percentile(latencies, 50),
            "p95": percentile(latencies, 95),
            "p99": percentile(latencies, 99),
            "max": round(max(latencies), 1) if latencies else None,
        },
        "statuses": {str(k): v for k, v in sorted(statuses.items())},
        "transport_errors": errors,
        "by_endpoint": {},
    }
    for endpoint, rows in sorted(by_endpoint.items()):
        endpoint_latencies = sorted(lat for _, lat, _ in rows if lat is not None)
        endpoint_statuses = {}
        for status, _, error in rows:
            key = error if error else str(status)
            endpoint_statuses[key] = endpoint_statuses.get(key, 0) + 1
        summary["by_endpoint"][endpoint] = {
            "requests": len(rows),
            "requests_per_second": round(len(rows) / elapsed_s, 1) if elapsed_s else 0,
            "p50_ms": percentile(endpoint_latencies, 50),
            "p95_ms": percentile(endpoint_latencies, 95),
            "statuses": endpoint_statuses,
        }
    return summary


def main():
    parser = argparse.ArgumentParser(description="Load test production-backend.")
    parser.add_argument("--base-url", default="http://localhost:8080",
                        help="base URL of the running API (default %(default)s)")
    parser.add_argument("--duration", type=int, default=30,
                        help="how long to run, in seconds (default %(default)s)")
    parser.add_argument("--concurrency", type=int, default=10,
                        help="number of concurrent workers (default %(default)s)")
    parser.add_argument("--output", help="also write the summary JSON to this file")
    parser.add_argument("--skip-health-check", action="store_true",
                        help="do not wait for /actuator/health first")
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    if not args.skip_health_check:
        print("Waiting for %s%s ..." % (base_url, HEALTH_PATH), flush=True)
        if not wait_for_health(base_url):
            print("ERROR: service did not become healthy at %s" % base_url, file=sys.stderr)
            return 2
        print("Service is UP.", flush=True)

    print("Running %d workers for %d seconds against %s ..."
          % (args.concurrency, args.duration, base_url), flush=True)
    results = []
    lock = threading.Lock()
    stop_at = time.time() + args.duration
    workers = [Worker(i, base_url, stop_at, results, lock)
               for i in range(args.concurrency)]
    started = time.perf_counter()
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join()
    elapsed_s = time.perf_counter() - started

    summary = summarise(results, elapsed_s)
    summary["base_url"] = base_url
    summary["duration_seconds"] = args.duration
    summary["concurrency"] = args.concurrency
    # Throughput over the intended window, as well as over the full
    # elapsed time (which includes requests still in flight when the
    # window ended draining afterwards). The window rate is the
    # load the app actually sustained; the elapsed rate is lower
    # whenever slow requests stretch the drain.
    summary["window_requests_per_second"] = round(
        summary["total_requests"] / args.duration, 1) if args.duration else 0
    print(json.dumps(summary, indent=2))
    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            json.dump(summary, handle, indent=2)
        print("Wrote %s" % args.output)

    failed = summary["transport_errors"] or any(
        status >= 500 for status in (int(k) for k in summary["statuses"]))
    rate_limited = summary["statuses"].get("429", 0)
    if rate_limited:
        print("NOTE: %d requests were rate-limited (429). The numbers "
              "above are the limiter's, not the app's; raise "
              "APP_RATE_LIMIT_REQUESTS_PER_MINUTE and re-run." % rate_limited)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
