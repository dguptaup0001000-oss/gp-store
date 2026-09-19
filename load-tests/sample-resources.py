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
    pg_idle_tx of those, ones idle inside a transaction - the dangerous state

USAGE
    sample-resources.py --pid 1234 --db gpstore_loadtest --out stage.csv \
                        --interval 2 [--duration 120]

Heap and GC are NOT sampled here. Asking the JVM for them every two seconds
perturbs the thing being measured; the -Xlog:gc log already records both at
every collection, and summarise-gc.py reads it afterwards for free.
"""

import argparse
import os
import subprocess
import sys
import time

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
           "count(*) FILTER (WHERE state='idle in transaction') "
           "FROM pg_stat_activity WHERE datname=%s" % ("'" + db + "'"))
    try:
        out = subprocess.run(['psql', '-U', os.environ.get('PGUSER', 'u0_a470'),
                              '-d', db, '-tA', '-F', ',', '-c', sql],
                             capture_output=True, text=True, timeout=5)
        return out.stdout.strip() or '0,0,0'
    except Exception:
        return '0,0,0'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--pid', type=int, required=True)
    ap.add_argument('--db', default='gpstore_loadtest')
    ap.add_argument('--out', required=True)
    ap.add_argument('--interval', type=float, default=2.0)
    ap.add_argument('--duration', type=float, default=0.0)
    args = ap.parse_args()

    cores = os.cpu_count() or 1
    started = time.time()
    last_ticks = proc_cpu_ticks(args.pid)
    last_time = started

    with open(args.out, 'w', buffering=1) as out:
        out.write('t,cpu_pct,cores,rss_mb,threads,load1,pg_total,pg_active,pg_idle_tx\n')
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
                out.write('%.1f,PROCESS_GONE,,,,,,,\n' % (now - started))
                return 1
            load1 = os.getloadavg()[0]
            out.write('%.1f,%.1f,%d,%.0f,%d,%.2f,%s\n'
                      % (now - started, cpu, cores, rss, threads, load1, pg_counts(args.db)))
            if args.duration and (now - started) >= args.duration:
                return 0


if __name__ == '__main__':
    sys.exit(main())
