#!/usr/bin/env python3
"""Build the Section 26 stage report from k6 JSON + monitor TSV.

Does not change k6 thresholds. Classifies PASS / FAIL / INFRASTRUCTURE_LIMIT.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path


def metric(data: dict, name: str, key: str = "count", default=0):
    values = data.get(name)
    if values is None:
        return default
    if isinstance(values, dict):
        if key in values:
            return values.get(key, default)
        if "count" in values:
            return values.get("count", default)
        if "value" in values:
            return values.get("value", default)
        return values.get(key, default)
    return values if values is not None else default


def threshold_ok(data: dict) -> tuple[bool, list[str]]:
    failed = []
    for name, spec in (data.get("thresholds") or {}).items():
        if not isinstance(spec, dict):
            continue
        for rule, result in spec.items():
            if isinstance(result, dict) and result.get("ok") is False:
                failed.append(f"{name}: {rule}")
    return (len(failed) == 0, failed)


def monitor_stats(path: Path) -> dict:
    if not path.exists():
        return {}
    rows = []
    with path.open() as fh:
        header = fh.readline()
        cols = header.strip().split("\t")
        for line in fh:
            parts = line.strip().split("\t")
            if len(parts) != len(cols):
                continue
            rows.append(dict(zip(cols, parts)))
    if not rows:
        return {}

    def nums(key):
        out = []
        for row in rows:
            try:
                out.append(float(row[key]))
            except (KeyError, ValueError):
                pass
        return out

    cpu = nums("cpu_pct")
    rss = nums("java_rss_mb")
    pg = nums("pg_backends")
    pg_act = nums("pg_active")
    redis_ops = nums("redis_ops")
    return {
        "samples": len(rows),
        "cpu_max": max(cpu) if cpu else None,
        "cpu_avg": (sum(cpu) / len(cpu)) if cpu else None,
        "rss_first": rss[0] if rss else None,
        "rss_max": max(rss) if rss else None,
        "rss_last": rss[-1] if rss else None,
        "pg_backends_max": max(pg) if pg else None,
        "pg_active_max": max(pg_act) if pg_act else None,
        "redis_ops_max": max(redis_ops) if redis_ops else None,
        "start_ts": rows[0].get("ts"),
        "end_ts": rows[-1].get("ts"),
    }


def classify_result(data: dict, mon: dict) -> tuple[str, str]:
    ok, failed = threshold_ok(data)
    n502 = metric(data, "status_502")
    n504 = metric(data, "status_504")
    n5xx = metric(data, "status_5xx")
    unexpected_503 = metric(data, "status_503_unexpected")
    net = metric(data, "status_network_error")
    app_err = metric(data, "err_application")
    db_err = metric(data, "err_database")
    test_data = metric(data, "err_test_data")
    cpu = mon.get("cpu_max") or 0
    rss_max = mon.get("rss_max") or 0
    pg_max = mon.get("pg_backends_max") or 0

    if n502 or n504 or unexpected_503 or app_err or db_err:
        return "FAIL", "Application or unexpected proxy/server errors are non-zero."
    if test_data:
        return "FAIL", "Checkout/cart hit missing inventory or leftover test SKUs (TEST_DATA_ERROR)."
    if net and n5xx == 0 and cpu < 70 and pg_max <= 15:
        return (
            "INFRASTRUCTURE_LIMIT",
            "Client dial/timeouts with a healthy JVM/Postgres and zero 5xx. "
            "The origin TCP door or thread pool is exhausted; this is not a 500 from Spring.",
        )
    if not ok:
        p95 = data.get("p95") or 0
        if n5xx == 0 and net == 0 and p95 and p95 > 1500 and cpu < 85:
            return (
                "INFRASTRUCTURE_LIMIT",
                "Latency gates failed with zero 5xx. Threads/connections are saturated; "
                "software still returned valid responses.",
            )
        return "FAIL", "k6 thresholds failed: " + "; ".join(failed[:8])
    return "PASS", "All existing k6 gates held. No 502, no unexpected 503, no network errors."


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: generate-stage-report.py <stage.json> [monitor.tsv] [out.txt]", file=sys.stderr)
        return 2
    summary_path = Path(sys.argv[1])
    monitor_path = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("/dev/null")
    out_path = Path(sys.argv[3]) if len(sys.argv) > 3 else summary_path.with_suffix(".report.txt")

    data = json.loads(summary_path.read_text())
    mon = monitor_stats(monitor_path)
    result, why = classify_result(data, mon)
    ok, failed = threshold_ok(data)
    stage = data.get("browseVus", 0) + data.get("cartVus", 0)

    lines = [
        "GP-STORE LOAD TEST",
        f"Stage: {stage} VUs (browse={data.get('browseVus')} cart={data.get('cartVus')})",
        f"Target: {data.get('baseUrl')}",
        f"Profile: warmup {data.get('warmupTime')} ramp {data.get('rampTime')} "
        f"hold {data.get('holdTime')} down {data.get('rampDownTime')}",
        "",
        f"RESULT: {result}",
        f"GATE_NOTE: {why}",
        "",
        "REQUESTS:",
        f"  total={data.get('httpReqs')} rps={round(data.get('rps') or 0, 2)} "
        f"iterations={data.get('iterations')} dropped={data.get('droppedIterations')}",
        "",
        "SUCCESS:",
        f"  http_2xx={metric(data, 'status_2xx')} checks_pass_rate={((data.get('checks') or {}).get('rate'))}",
        "",
        "ERROR:",
        f"  network={metric(data, 'status_network_error')} 4xx={metric(data, 'status_4xx')} "
        f"5xx={metric(data, 'status_5xx')}",
        f"  catalog_errors={metric(data, 'catalog_errors')} product_errors={metric(data, 'product_errors')} "
        f"search_errors={metric(data, 'search_errors')}",
        f"  cart_errors={metric(data, 'cart_errors')} checkout_errors={metric(data, 'checkout_errors')} "
        f"order_errors={metric(data, 'order_errors')} auth_errors={metric(data, 'auth_errors')}",
        "",
        "HTTP STATUS:",
        f"  HTTP 400: {metric(data, 'http_400')}",
        f"  HTTP 401: {metric(data, 'http_401')}",
        f"  HTTP 403: {metric(data, 'http_403')}",
        f"  HTTP 404: {metric(data, 'http_404')}",
        f"  HTTP 409: {metric(data, 'http_409')}",
        f"  HTTP 429: {metric(data, 'http_429')}",
        f"  HTTP 500: {metric(data, 'http_500')}",
        f"  HTTP 502: {metric(data, 'http_502')}",
        f"  HTTP 503: {metric(data, 'http_503')} (shed={metric(data, 'status_503_shed')} "
        f"unexpected={metric(data, 'status_503_unexpected')})",
        f"  HTTP 504: {metric(data, 'http_504')}",
        "",
        "CLASSIFICATION:",
        f"  CLIENT_ERROR={metric(data, 'err_client')} AUTH_ERROR={metric(data, 'err_auth')} "
        f"RATE_LIMIT={metric(data, 'err_rate_limit')}",
        f"  APPLICATION_ERROR={metric(data, 'err_application')} DATABASE_ERROR={metric(data, 'err_database')} "
        f"REDIS_ERROR={metric(data, 'err_redis')}",
        f"  TIMEOUT={metric(data, 'err_timeout')} CONNECTION_ERROR={metric(data, 'err_connection')}",
        f"  PROXY_502={metric(data, 'err_proxy_502')} PROXY_503={metric(data, 'err_proxy_503')} "
        f"PROXY_504={metric(data, 'err_proxy_504')}",
        f"  INFRASTRUCTURE_CAPACITY={metric(data, 'err_infra_capacity')} "
        f"TEST_DATA_ERROR={metric(data, 'err_test_data')} UNKNOWN={metric(data, 'err_unknown')}",
        f"  EXPECTED_PROTECTIVE_503={metric(data, 'err_expected_protective_503')} "
        f"UNEXPECTED_503={metric(data, 'err_unexpected_503')}",
        "",
        "LATENCY:",
        f"  P95={data.get('p95')} P99={data.get('p99')} P50={data.get('p50')} max={data.get('maxLatency')}",
        f"  catalog_p95={data.get('catalog_p95')} product_p95={data.get('product_p95')} "
        f"search_p95={data.get('search_p95')}",
        f"  cart_p95={data.get('cart_p95')} checkout_p95={data.get('checkout_p95')} "
        f"order_p95={data.get('order_p95')} auth_p95={data.get('auth_p95')}",
        "",
        f"RPS: {round(data.get('rps') or 0, 2)}",
        f"ORDERS: placed={metric(data, 'ordersPlaced')} duplicate_replay_ok={metric(data, 'orders_idempotent_ok')} "
        f"rate_limited={metric(data, 'ordersRateLimited')} rejected={metric(data, 'ordersRejected')}",
        f"RETRIES: attempted={metric(data, 'retries_attempted')} skipped_permanent={metric(data, 'retries_skipped_permanent')}",
        "",
        "DB:",
        f"  pg_backends_max={mon.get('pg_backends_max')} pg_active_max={mon.get('pg_active_max')} "
        f"(Hikari max remains 10 unless the instance env changed it)",
        "",
        "REDIS:",
        f"  ops_max={mon.get('redis_ops_max')}",
        "",
        "CPU:",
        f"  max={mon.get('cpu_max')} avg={mon.get('cpu_avg')}",
        "",
        "RAM:",
        f"  rss_before={mon.get('rss_first')} rss_max={mon.get('rss_max')} rss_after={mon.get('rss_last')} MB",
        "",
        "JVM / TOMCAT:",
        "  See instance env: DB_POOL_MAX_SIZE default 10, TOMCAT_MAX_THREADS default 40, "
        "TOMCAT_MAX_CONNECTIONS default 500. Do not raise those in git without a capacity budget.",
        "",
        "ROOT CAUSE:",
        f"  {why}",
        "",
        "ACTION TAKEN:",
        "  Recorded in the PR for this stage. Thresholds were not relaxed.",
        "",
        f"RETEST REQUIRED: {'YES' if result != 'PASS' else 'NO'}",
        "",
        "NEXT STAGE:",
        "  1000 VUs only if this gate is PASS. INFRASTRUCTURE_LIMIT stops the VU ladder "
        "until hardware/config is sized on purpose — not by hiding failures.",
        "",
        "FAILED_THRESHOLDS:" if failed else "FAILED_THRESHOLDS: none",
    ]
    for item in failed:
        lines.append(f"  - {item}")
    if mon.get("start_ts"):
        lines.extend(["", f"MONITOR_WINDOW: {mon.get('start_ts')} -> {mon.get('end_ts')} samples={mon.get('samples')}"])

    text = "\n".join(lines) + "\n"
    out_path.write_text(text)
    print(text)
    gate_path = summary_path.with_suffix(".gate")
    gate_path.write_text(result + "\n")
    return 0 if result == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
