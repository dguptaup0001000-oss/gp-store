#!/usr/bin/env python3
"""One honest line per stage, in the vocabulary the request asked for.

k6's own summary is long and mixes deliberate refusals in with faults. What a
capacity report needs is the split: how many requests were SERVED, how many
were refused ON PURPOSE (throttled or shed), and how many were faults - and
then the server-side numbers from the same window, because latency without
CPU, pool and connection state is a symptom with no cause attached.

DELIBERATE REFUSALS ARE NOT SUCCESSES. served_ratio counts only 2xx. A
stage where a third of requests were rate-limited is reported as a third of
requests refused, never as "no errors".
"""

import argparse
import csv
import json


def metric(summary, name, field='count'):
    m = summary.get('metrics', {}).get(name)
    if not m:
        return 0
    return m.get(field, 0) or 0


def trend(summary, name, field):
    m = summary.get('metrics', {}).get(name) or {}
    return m.get(field)


def resources(path):
    rows = []
    try:
        with open(path) as f:
            for row in csv.DictReader(f):
                if row.get('cpu_pct') in (None, '', 'PROCESS_GONE'):
                    continue
                rows.append(row)
    except OSError:
        return None
    if not rows:
        return None
    def peak(col, cast=float):
        values = [cast(r[col]) for r in rows if r.get(col)]
        return max(values) if values else 0
    def mean(col):
        vals = [float(r[col]) for r in rows if r.get(col)]
        return sum(vals) / len(vals) if vals else 0.0
    host_cpu = [float(r['system_cpu_load']) * 100 for r in rows
                if r.get('system_cpu_load') and float(r['system_cpu_load']) >= 0]
    return {
        'cores': rows[0]['cores'],
        'cpu_mean': mean('cpu_pct'),
        'cpu_peak': peak('cpu_pct'),
        'rss_peak_mb': peak('rss_mb'),
        'host_cpu_peak_pct': max(host_cpu, default=0),
        'host_memory_total_mb': peak('system_memory_total_mb'),
        'host_memory_free_min_mb': min(
            (float(r['system_memory_free_mb']) for r in rows if r.get('system_memory_free_mb')),
            default=0),
        'threads_peak': peak('threads', int),
        'load1_peak': peak('load1'),
        'pg_total_peak': peak('pg_total', int),
        'pg_active_peak': peak('pg_active', int),
        'pg_idle_tx_peak': peak('pg_idle_tx', int),
        'hikari_active_peak': peak('hikari_active', int),
        'hikari_waiting_peak': peak('hikari_waiting', int),
        'hikari_total_peak': peak('hikari_total', int),
        'hikari_max': peak('hikari_max', int),
        'heap_used_peak_mb': peak('heap_used_mb'),
        'heap_max_mb': peak('heap_max_mb'),
        'gc_count_delta': max(0, int(rows[-1].get('gc_count') or 0)
                               - int(rows[0].get('gc_count') or 0)),
        'gc_time_delta_ms': max(0, int(rows[-1].get('gc_time_ms') or 0)
                                - int(rows[0].get('gc_time_ms') or 0)),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--label', required=True)
    ap.add_argument('--summary', required=True)
    ap.add_argument('--resources')
    ap.add_argument('--k6-exit', type=int, default=0)
    args = ap.parse_args()

    with open(args.summary) as f:
        s = json.load(f)

    total = metric(s, 'http_reqs')
    ok = metric(s, 'requests_ok')
    throttled = metric(s, 'status_429')
    shed = metric(s, 'status_503_shed')
    expected4xx = metric(s, 'status_4xx_expected')
    faults = {
        '500': metric(s, 'status_500'),
        '502': metric(s, 'status_502'),
        '503_unexpected': metric(s, 'status_503_unexpected'),
        '4xx_unexpected': metric(s, 'status_4xx_unexpected'),
        'network': metric(s, 'status_network_error'),
        'timeout': metric(s, 'status_timeout'),
    }

    print('=' * 72)
    print('STAGE %s  %s' % (args.label, 'PASSED GATES' if args.k6_exit == 0 else 'BROKE A GATE'))
    print('  requests            %d' % total)
    served_pct = 100.0 * ok / total if total else 0
    print('  served (2xx)        %d  (%.2f%%)' % (ok, served_pct))
    print('  refused on purpose  %d rate-limited (429), %d shed (503)' % (throttled, shed))
    print('  answered 4xx        %d  (401/403/404/409/410/422 - an answer, not a fault)'
          % expected4xx)
    print('  faults              ' + ', '.join('%s=%d' % (k, v) for k, v in faults.items()))
    for name in ('discovery', 'shelf', 'search', 'market_feed', 'market_search',
                 'market_offers', 'product_detail', 'cart_add', 'cart_read'):
        key = 'http_req_duration{name:%s}' % name
        p95 = trend(s, key, 'p(95)')
        if p95 is None:
            continue
        print('  %-16s p50=%sms p95=%sms p99=%sms max=%sms'
              % (name,
                 fmt(trend(s, key, 'med')), fmt(p95),
                 fmt(trend(s, key, 'p(99)')), fmt(trend(s, key, 'max'))))
    r = resources(args.resources) if args.resources else None
    if r:
        print('  server  app cpu mean %.0f%% peak %.0f%% of %s cores; host cpu peak %.0f%%; '
              'RSS peak %.0f MB; host RAM free min %.0f/%.0f MB; threads peak %d; load1 peak %.2f'
              % (r['cpu_mean'], r['cpu_peak'], r['cores'], r['host_cpu_peak_pct'],
                 r['rss_peak_mb'], r['host_memory_free_min_mb'],
                 r['host_memory_total_mb'], r['threads_peak'], r['load1_peak']))
        print('  postgres backends peak %d, executing peak %d, idle-in-transaction peak %d'
              % (r['pg_total_peak'], r['pg_active_peak'], r['pg_idle_tx_peak']))
        print('  Hikari   active peak %d/%d, waiting peak %d, total peak %d'
              % (r['hikari_active_peak'], r['hikari_max'], r['hikari_waiting_peak'],
                 r['hikari_total_peak']))
        print('  JVM      heap peak %.0f/%.0f MB, GC count +%d, GC time +%d ms'
              % (r['heap_used_peak_mb'], r['heap_max_mb'], r['gc_count_delta'],
                 r['gc_time_delta_ms']))
    else:
        print('  server  NOT SAMPLED')
    print()


def fmt(v):
    return '-' if v is None else ('%.0f' % v)


if __name__ == '__main__':
    main()
