#!/usr/bin/env python3
"""
A REPEATABLE MARKETPLACE LOAD MEASUREMENT.

WHAT IT IS FOR. Nobody may quote a concurrency figure for GP-STORE that
somebody has not measured. This exists so the figure can be measured the same
way twice: same endpoints, same mix, same percentiles, same output, so two
runs a month apart are comparable and a regression is visible as a number
rather than as a feeling.

WHAT IT MEASURES. The read path a customer actually exercises before they buy
anything - discovery, a storefront, the catalogue, a search, a product - which
is where the traffic is and where a marketplace falls over first. Writes
(add-to-cart, checkout) are DELIBERATELY EXCLUDED by default: they create
orders and touch payment rows, and a load harness that leaves a thousand
half-finished orders behind is one nobody will run twice. Pass --with-writes
only against a database you are willing to throw away.

WHAT IT DOES NOT DO. It does not use real payment credentials, and there is no
code path here that could: the endpoints it calls are reads.

HONESTY ABOUT THE NUMBERS. The harness and the server share one machine in
this environment, so at high concurrency the client competes with the thing it
is measuring and the latencies include that contention. That does not make the
measurement useless - it makes it a floor, and the report must say which it is.
"""
import argparse
import json
import os
import queue
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request

DEFAULT_BASE = os.environ.get("LOAD_BASE_URL", "http://localhost:8080")


class Endpoint:
    """One request the harness knows how to make, and what it is called."""

    def __init__(self, name, path):
        self.name = name
        self.path = path


def default_mix(lat, lng, shop_id, product_id, variant_id):
    """
    The journey in the proportions a real one has.

    Discovery and browsing dominate; the storefront and the product are opened
    less often than the list that leads to them; search is bursty. Weighting is
    by repetition in this list, which keeps the mix readable.
    """
    mix = [
        Endpoint("discovery", f"/api/marketplace/discovery?lat={lat}&lng={lng}"),
        Endpoint("discovery", f"/api/marketplace/discovery?lat={lat}&lng={lng}"),
        Endpoint("discovery", f"/api/marketplace/discovery?lat={lat}&lng={lng}"),
        Endpoint("shops-near", f"/api/marketplace/shops?lat={lat}&lng={lng}"),
        Endpoint("storefront", f"/api/marketplace/shops/{shop_id}"),
        Endpoint("storefront", f"/api/marketplace/shops/{shop_id}"),
        Endpoint("feed", "/api/products/feed?page=0&size=20"),
        Endpoint("feed", "/api/products/feed?page=0&size=20"),
        Endpoint("categories", "/api/categories"),
        Endpoint("search", "/api/products/search/instant?q=dal"),
        Endpoint("product", f"/api/products/{product_id}"),
        Endpoint("mode", "/api/marketplace/mode"),
    ]
    if variant_id:
        mix.append(Endpoint("compare", f"/api/discovery/compare?variantId={variant_id}"
                                       f"&lat={lat}&lng={lng}"))
    return mix


class Result:
    def __init__(self):
        self.lock = threading.Lock()
        self.latencies = []
        self.by_endpoint = {}
        self.errors = 0
        self.by_status = {}

    def record(self, name, seconds, status):
        with self.lock:
            self.latencies.append(seconds)
            self.by_endpoint.setdefault(name, []).append(seconds)
            self.by_status[status] = self.by_status.get(status, 0) + 1
            # A 4xx is a failure of the harness's fixture; a 5xx or a dropped
            # connection is a failure of the thing being measured. Both count
            # as errors and both are reported by code, because a run that is
            # 100% 404 must never be readable as "fast".
            if not isinstance(status, int) or status >= 400:
                self.errors += 1


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    k = (len(ordered) - 1) * (p / 100.0)
    low = int(k)
    high = min(low + 1, len(ordered) - 1)
    if low == high:
        return ordered[low]
    return ordered[low] + (ordered[high] - ordered[low]) * (k - low)


def worker(base, work, result, token, stop_at):
    opener = urllib.request.build_opener()
    while time.time() < stop_at:
        try:
            endpoint = work.get_nowait()
        except queue.Empty:
            return
        request = urllib.request.Request(base + endpoint.path)
        if token:
            request.add_header("Authorization", "Bearer " + token)
        started = time.perf_counter()
        try:
            with opener.open(request, timeout=30) as response:
                response.read()
                status = response.status
        except urllib.error.HTTPError as http_error:
            status = http_error.code
        except Exception as failure:                      # noqa: BLE001
            status = type(failure).__name__
        result.record(endpoint.name, time.perf_counter() - started, status)


def run_level(base, concurrency, requests_per_level, mix, token, budget_seconds):
    work = queue.Queue()
    for i in range(requests_per_level):
        work.put(mix[i % len(mix)])

    result = Result()
    stop_at = time.time() + budget_seconds
    threads = [
        threading.Thread(target=worker, args=(base, work, result, token, stop_at),
                         daemon=True)
        for _ in range(concurrency)
    ]

    wall_start = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=budget_seconds + 5)
    wall = time.perf_counter() - wall_start

    done = len(result.latencies)
    return {
        "concurrency": concurrency,
        "requests": done,
        "wall_seconds": round(wall, 2),
        "throughput_rps": round(done / wall, 1) if wall > 0 else 0,
        "error_rate": round(result.errors / done, 4) if done else 1.0,
        "p50_ms": round(percentile(result.latencies, 50) * 1000, 1),
        "p95_ms": round(percentile(result.latencies, 95) * 1000, 1),
        "p99_ms": round(percentile(result.latencies, 99) * 1000, 1),
        "max_ms": round(max(result.latencies) * 1000, 1) if result.latencies else 0,
        "status_counts": {str(k): v for k, v in sorted(result.by_status.items(), key=str)},
        "by_endpoint_p95_ms": {
            name: round(percentile(values, 95) * 1000, 1)
            for name, values in sorted(result.by_endpoint.items())
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default=DEFAULT_BASE)
    parser.add_argument("--levels", default="100,250,500,1000",
                        help="concurrency levels, comma separated")
    parser.add_argument("--requests", type=int, default=2000,
                        help="requests per level")
    parser.add_argument("--budget", type=int, default=120,
                        help="seconds a level may take before it is cut short")
    parser.add_argument("--lat", default="12.9")
    parser.add_argument("--lng", default="77.6")
    parser.add_argument("--shop", default="1")
    parser.add_argument("--product", default="1")
    parser.add_argument("--variant", default="")
    parser.add_argument("--token", default=os.environ.get("LOAD_TOKEN", ""))
    parser.add_argument("--out", default="")
    args = parser.parse_args()

    mix = default_mix(args.lat, args.lng, args.shop, args.product, args.variant)

    # WARM FIRST, MEASURE SECOND. The first request through a cold JIT, a cold
    # connection pool and an empty cache is not the number anybody is asking
    # about, and including it makes the lowest concurrency level look like the
    # worst one.
    print("warming up...", file=sys.stderr)
    run_level(args.base, 4, 80, mix, args.token, 60)

    runs = []
    for level in [int(x) for x in args.levels.split(",") if x.strip()]:
        print(f"level {level}...", file=sys.stderr)
        measured = run_level(args.base, level, args.requests, mix, args.token, args.budget)
        runs.append(measured)
        print(json.dumps(measured), flush=True)

    report = {"base": args.base, "runs": runs}
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
