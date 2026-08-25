// Staged browse-only capacity probe.
//
// Purpose: find the first concurrent-VU level that is still healthy, then
// STOP. This is not a 5,000-VU marketing run. Previous 5k–10k runs against
// production produced 95–99% failures and 502/503s; those numbers measured
// overload of one small instance (~40 Tomcat threads, 10 DB connections),
// not application correctness.
//
// Default target is localhost:8081 (the app's default PORT). Do not point
// this at production unless you intend to load the live shop.
//
//   BASE_URL=http://localhost:8081/v1 VUS=10 HOLD_TIME=20s k6 run staged-capacity.js
//
// Pass/fail (hard gates — a failed stage must not continue):
//   - p95 < 2s on catalog reads
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
// Each VU behaves like a shopper: category list → a product page → 10%
// search, with 1.5–4s think time. Health is probed once in setup(), not
// once per iteration.
//
// Pool / CPU / memory are not visible to k6. Record those from the process
// and from Postgres (`pg_stat_activity`, Hikari logs) in the same window.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8081/v1').replace(/\/$/, '');
const VUS = Number(__ENV.VUS || 10);
const HOLD_TIME = __ENV.HOLD_TIME || '20s';

const status502 = new Counter('status_502');
const status503Shed = new Counter('status_503_shed');
const status503Unexpected = new Counter('status_503_unexpected');
const statusNetworkError = new Counter('status_network_error');
const status429 = new Counter('status_429');
const bytesReceived = new Counter('response_bytes');
const browseDuration = new Trend('browse_duration', true);

function isCatalogShed(res) {
  if (res.status !== 503) return false;
  const shedHeader = res.headers['X-GP-Shed'] || res.headers['x-gp-shed'];
  if (shedHeader) return true;
  const body = typeof res.body === 'string' ? res.body : '';
  return body.indexOf('POOL_SATURATED') >= 0 || body.indexOf('The shop is busy') >= 0;
}

function record(res, catalog) {
  bytesReceived.add(res.body ? res.body.length : 0);
  if (res.status === 0) statusNetworkError.add(1);
  else if (res.status === 502) status502.add(1);
  else if (res.status === 429) status429.add(1);
  else if (res.status === 503) {
    if (catalog && isCatalogShed(res)) status503Shed.add(1);
    else status503Unexpected.add(1);
  }
}

export const options = {
  scenarios: {
    browse: {
      executor: 'constant-vus',
      vus: VUS,
      duration: HOLD_TIME,
    },
  },
  thresholds: {
    'http_req_duration{name:categories}': ['p(95)<2000', 'p(99)<4000'],
    'http_req_duration{name:browse_category}': ['p(95)<2000', 'p(99)<4000'],
    status_502: ['count==0'],
    status_503_unexpected: ['count==0'],
    status_network_error: ['count==0'],
  },
};

const SEARCH_TERMS = ['rice', 'oil', 'soap', 'milk', 'atta'];

http.setResponseCallback(
  http.expectedStatuses({ min: 200, max: 399 }, 429, 503),
);

export function setup() {
  const health = http.get(`${BASE_URL}/api/health`, { tags: { name: 'liveness' } });
  record(health, false);
  check(health, { 'setup liveness 200': (r) => r.status === 200 });
  if (health.status !== 200) {
    throw new Error(`liveness failed with HTTP ${health.status} - refusing to start a load stage`);
  }

  const cats = http.get(`${BASE_URL}/api/categories`, { tags: { name: 'categories' } });
  record(cats, true);
  const list = cats.status === 200 ? cats.json() : [];
  if (!list || list.length === 0) {
    throw new Error('No categories returned - is the catalog seeded?');
  }
  return { categories: list };
}

export default function (data) {
  const category = data.categories[Math.floor(Math.random() * data.categories.length)];

  const cats = http.get(`${BASE_URL}/api/categories`, { tags: { name: 'categories' } });
  browseDuration.add(cats.timings.duration);
  record(cats, true);
  check(cats, { 'categories 200 or shed 503': (r) => r.status === 200 || isCatalogShed(r) });

  sleep(1.5 + Math.random() * 2.5);

  const page = http.get(`${BASE_URL}/api/products/category/${category.id}?page=0&size=20`, {
    tags: { name: 'browse_category' },
  });
  browseDuration.add(page.timings.duration);
  record(page, true);
  check(page, { 'category products 200 or shed 503': (r) => r.status === 200 || isCatalogShed(r) });

  if (Math.random() < 0.1) {
    sleep(1.5 + Math.random() * 2.5);
    const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
    const search = http.get(
      `${BASE_URL}/api/products/search/instant?keyword=${term}&page=0&size=20`,
      { tags: { name: 'search' } },
    );
    record(search, true);
  }

  sleep(1.5 + Math.random() * 2.5);
}
