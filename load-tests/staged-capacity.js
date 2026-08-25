// Staged browse-only capacity probe.
//
// Purpose: find the first concurrent-VU level that is still healthy, then
// STOP. This is not a 5,000-VU marketing run. Previous 5k–10k runs against
// production produced 95–99% failures and 502/503s; those numbers measured
// overload, not capacity.
//
// Default target is localhost. Do not point this at production unless you
// intend to load the live shop.
//
//   BASE_URL=http://localhost:8080/v1 VUS=10 HOLD_TIME=20s k6 run staged-capacity.js
//
// Pass/fail (hard gates — a failed stage must not continue):
//   - p95 < 2s
//   - p99 < 4s
//   - status_502 == 0
//   - status_503_unexpected == 0 (liveness 503, not catalog shed)
//   - status_network_error == 0
//
// Catalog GET 503 from PoolSaturationFilter is counted as status_503_shed
// and does NOT fail the stage. That is the app refusing browse work so
// checkout can keep a connection. A 502 is still a hard stop: the proxy
// gave up, which is not intentional shedding.
//
// Pool / CPU / memory are not visible to k6. Record those from the process
// and from Postgres (`pg_stat_activity`, Hikari logs) in the same window.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/v1';
const VUS = Number(__ENV.VUS || 10);
const HOLD_TIME = __ENV.HOLD_TIME || '20s';

const status502 = new Counter('status_502');
const status503Shed = new Counter('status_503_shed');
const status503Unexpected = new Counter('status_503_unexpected');
const statusNetworkError = new Counter('status_network_error');
const browseDuration = new Trend('browse_duration', true);

export const options = {
  scenarios: {
    browse: {
      executor: 'constant-vus',
      vus: VUS,
      duration: HOLD_TIME,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<4000'],
    status_502: ['count==0'],
    status_503_unexpected: ['count==0'],
    status_network_error: ['count==0'],
  },
};

export default function () {
  const health = http.get(`${BASE_URL}/api/health`, { tags: { name: 'liveness' } });
  if (health.status === 0) statusNetworkError.add(1);
  if (health.status === 502) status502.add(1);
  if (health.status === 503) status503Unexpected.add(1);
  check(health, { 'liveness 200': (r) => r.status === 200 });

  // Do not probe /ready on every VU iteration. Ready borrows a DB connection
  // when the pool has idle ones; 5,000 VUs doing that was enough to starve
  // catalog requests and produce origin timeouts (Render 502).
  const cats = http.get(`${BASE_URL}/api/categories`, { tags: { name: 'categories' } });
  browseDuration.add(cats.timings.duration);
  if (cats.status === 0) statusNetworkError.add(1);
  if (cats.status === 502) status502.add(1);
  if (cats.status === 503) status503Shed.add(1);
  check(cats, { 'categories 200 or shed 503': (r) => r.status === 200 || r.status === 503 });

  sleep(1);
}
