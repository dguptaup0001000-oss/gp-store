#!/usr/bin/env python3
"""Samples the server side of a load stage, because k6 cannot see any of it.

k6 reports what a client experienced. It has nothing to say about why: whether
the JVM was saturated, whether the connection pool had a queue behind it,
whether Postgres was the thing that was busy. A stage report without these
numbers can tell you that latency rose and not one reason it might have.

Writes one CSV row per interval:

    t          seconds since the sampler started
    cpu_pct    the application process's CPU, as a percentage of ONE core
    cores      how many cores exist, so cpu_pct can be read against them
    rss_mb     resident set size of the JVM process
    threads    OS threads in the JVM
    load1      the machine's one-minute load average
    pg_total   backends connected to the load-test database
    pg_active  of those, ones currently executing a statement
    pg_waiting active statements waiting on a PostgreSQL lock or other event
    pg_idle_tx of those, ones idle inside a transaction - the dangerous state
    Hikari active/idle/waiting/max, heap, GC counts/time, system CPU and RAM

USAGE
    sample-resources.py --pid 1234 --db gpstore_loadtest --out stage.csv \
                        --interval 2 [--duration 120]

The JVM runtime snapshot is taken at each interval through the health endpoint.
It exposes pool, heap, GC, CPU and host-memory counters and does no database
or Redis work.
"""

import argparse
import json
import os
import subprocess
import sys
import time
from urllib.request import urlopen

CLOCK_TICKS = os.sysconf('SC_CLK_TCK')


def proc_cpu_ticks(pid):
    with open(f'/proc/{pid}/stat') as f:
        fields = f.read().rsplit(')', 1)[1].split()
    # utime and stime are fields 14 and 15 one-based; the split above drops
    # the first two, so they land at index 11 and 12.
    return int(fields[11]) + int(fields[12])


def proc_rss_mb(pid):
    with open(f'/proc/{pid}/statm') as f:
        pages = int(f.read().split()[1])
    return pages * os.sysconf('SC_PAGE_SIZE') / (1024 * 1024)


def proc_threads(pid):
    with open(f'/proc/{pid}/status') as f:
        for line in f:
            if line.startswith('Threads:'):
                return int(line.split()[1])
    return 0


def pg_counts(db):
    sql = ("SELECT count(*), count(*) FILTER (WHERE state='active'), "
           "count(*) FILTER (WHERE state='active' AND wait_event_type IS NOT NULL), "
           "count(*) FILTER (WHERE state='idle in transaction') "
           "FROM pg_stat_activity WHERE datname=%s" % ("'" + db + "'"))
    try:
        out = subprocess.run(['psql', '-U', os.environ.get('PGUSER', 'gpstore'),
                              '-d', db, '-tA', '-F', ',', '-c', sql],
                             capture_output=True, text=True, timeout=5)
        return out.stdout.strip() or '0,0,0,0'
    except Exception:
        return '0,0,0,0'


def runtime_snapshot(url):
    names = {
        'hikari_active': 'hikariActive', 'hikari_idle': 'hikariIdle',
        'hikari_waiting': 'hikariWaiting', 'hikari_total': 'hikariTotal',
        'hikari_max': 'hikariMax', 'heap_used_mb': 'heapUsedMb',
        'heap_max_mb': 'heapMaxMb', 'gc_count': 'gcCount',
        'gc_time_ms': 'gcTimeMs', 'system_cpu_load': 'systemCpuLoad',
        'process_cpu_load': 'processCpuLoad',
        'system_memory_total_mb': 'systemMemoryTotalMb',
        'system_memory_free_mb': 'systemMemoryFreeMb',
    }
    try:
        with urlopen(url, timeout=1.5) as response:
            data = json.loads(response.read())
        return {key: data.get(value, '') for key, value in names.items()}
    except Exception:
        return {key: '' for key in names}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--pid', type=int, required=True)
    ap.add_argument('--db', default='gpstore_loadtest')
    ap.add_argument('--out', required=True)
    ap.add_argument('--interval', type=float, default=2.0)
    ap.add_argument('--duration', type=float, default=0.0)
    ap.add_argument('--runtime-url', default='http://127.0.0.1:8081/v1/api/health/runtime')
    args = ap.parse_args()

    cores = os.cpu_count() or 1
    started = time.time()
    last_ticks = proc_cpu_ticks(args.pid)
    last_time = started

    with open(args.out, 'w', buffering=1) as out:
        runtime_fields = ['hikari_active', 'hikari_idle', 'hikari_waiting',
                          'hikari_total', 'hikari_max', 'heap_used_mb',
                          'heap_max_mb', 'gc_count', 'gc_time_ms',
                          'system_cpu_load', 'process_cpu_load',
                          'system_memory_total_mb', 'system_memory_free_mb']
        out.write('t,cpu_pct,cores,rss_mb,threads,load1,pg_total,pg_active,pg_waiting,pg_idle_tx,'
                  + ','.join(runtime_fields) + '\n')
        while True:
            time.sleep(args.interval)
            now = time.time()
            try:
                ticks = proc_cpu_ticks(args.pid)
                cpu = (ticks - last_ticks) / CLOCK_TICKS / (now - last_time) * 100.0
                last_ticks, last_time = ticks, now
                rss = proc_rss_mb(args.pid)
                threads = proc_threads(args.pid)
            except FileNotFoundError:
                # The process being measured is gone. That is itself a result.
                gone = ['%.1f' % (now - started), 'PROCESS_GONE'] + [''] * 21
                out.write(','.join(gone) + '\n')
                return 1
            load1 = os.getloadavg()[0]
            pg = (pg_counts(args.db).split(',') + ['', '', '', ''])[:4]
            runtime = runtime_snapshot(args.runtime_url)
            values = [now - started, cpu, cores, rss, threads, load1, *pg,
                      *(runtime[field] for field in runtime_fields)]
            out.write(','.join(str(value) if value is not None else '' for value in values) + '\n')
            if args.duration and (now - started) >= args.duration:
                return 0


if __name__ == '__main__':
    sys.exit(main())
