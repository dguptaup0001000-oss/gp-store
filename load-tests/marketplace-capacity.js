// Staged capacity probe for the MARKETPLACE read path.
//
// WHY A SECOND SCRIPT AND NOT A CHANGE TO staged-capacity.js. That one
// measures the single-shop browse path - categories, feed, search - which is
// what a customer did before there was a marketplace. This measures what they
// do FIRST now: find the shops that will deliver to them, open one, and
// compare a price across several. Those are different queries with a
// different cost shape (ShopDiscovery does a per-shop radius test, the
// storefront reads that shop's trading record and ratings inside its own
// scope, and compare fans out across every shop in reach), so averaging them
// into the browse numbers would hide whichever is slower.
//
// Default target is localhost:8081. Do not point this at production unless
// you intend to load the live shop.
//
//   BASE_URL=http://localhost:8081/v1 VUS=10 HOLD_TIME=20s k6 run marketplace-capacity.js
//
// Optional, and worth setting - the defaults are only a guess at your data:
//   LAT / LNG        a point a shop actually serves (default: Shop #1's own)
//   SHOP_ID          a shop the marketplace shows to customers
//   VARIANT_ID       a variant several shops list, to exercise /discovery/compare
//
// Pass/fail (hard gates - a failed stage must not continue):
//   - p95 < 2s on discovery and storefront reads
//   - p99 < 4s
//   - status_502 == 0
//   - status_503_unexpected == 0
//   - status_network_error == 0
//
// WHAT THIS CANNOT TELL YOU. Pool, CPU and memory are invisible to k6;
// record them from the process and from pg_stat_activity in the same window.
// And a VU is not a user: read the throughput (http_reqs/s), not the VU
// count, and never quote a "concurrent users supported" figure from it.
//
// READS ONLY, deliberately. Nothing here places an order, touches a payment
// row or needs a credential, so it leaves no rows behind and cannot reach a
// payment provider. Checkout load lives in browse-cart-checkout.js, which
// seeds its own accounts on purpose.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8081/v1').replace(/\/$/, '');
const VUS = Number(__ENV.VUS || 10);
const HOLD_TIME = __ENV.HOLD_TIME || '20s';

// Shop #1's own coordinates, because a pin no shop serves makes discovery
// answer an empty list very fast and measures nothing.
const LAT = __ENV.LAT || '27.16231';
const LNG = __ENV.LNG || '83.940468';
const SHOP_ID = __ENV.SHOP_ID || '1';
const VARIANT_ID = __ENV.VARIANT_ID || '';

const status502 = new Counter('status_502');
const status503Shed = new Counter('status_503_shed');
const status503Unexpected = new Counter('status_503_unexpected');
const statusNetworkError = new Counter('status_network_error');
const status429 = new Counter('status_429');
const status401 = new Counter('status_401');
const bytesReceived = new Counter('response_bytes');
const discoveryDuration = new Trend('discovery_duration', true);

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
  // A 401 here is a fixture problem, not a capacity one - /discovery/compare
  // needs a signed-in customer. Counted separately so a run that is entirely
  // 401 cannot be read as a fast one.
  else if (res.status === 401 || res.status === 403) status401.add(1);
  else if (res.status === 503) {
    if (catalog && isCatalogShed(res)) status503Shed.add(1);
    else status503Unexpected.add(1);
  }
}

export const options = {
  scenarios: {
    marketplace: {
      executor: 'constant-vus',
      vus: VUS,
      duration: HOLD_TIME,
    },
  },
  thresholds: {
    'http_req_duration{name:discovery}': ['p(95)<2000', 'p(99)<4000'],
    'http_req_duration{name:shops_near}': ['p(95)<2000', 'p(99)<4000'],
    'http_req_duration{name:storefront}': ['p(95)<2000', 'p(99)<4000'],
    status_502: ['count==0'],
    status_503_unexpected: ['count==0'],
    status_network_error: ['count==0'],
  },
};

http.setResponseCallback(
  http.expectedStatuses({ min: 200, max: 399 }, 401, 403, 429, 503),
);

export function setup() {
  const health = http.get(`${BASE_URL}/api/health`, { tags: { name: 'liveness' } });
  record(health, false);
  check(health, { 'setup liveness 200': (r) => r.status === 200 });
  if (health.status !== 200) {
    throw new Error(`liveness failed with HTTP ${health.status} - refusing to start a load stage`);
  }

  // REFUSE TO MEASURE AN EMPTY MARKETPLACE. Discovery against a pin no shop
  // serves returns [] in a millisecond, and a run of those would look like
  // the fastest stage ever recorded while testing nothing at all.
  const probe = http.get(`${BASE_URL}/api/marketplace/discovery?lat=${LAT}&lng=${LNG}`, {
    tags: { name: 'discovery' },
  });
  record(probe, true);
  if (probe.status !== 200) {
    throw new Error(`discovery answered HTTP ${probe.status} in setup - not a capacity result`);
  }
  const page = probe.json();
  const found = page && page.shops ? page.shops.length : 0;
  if (found === 0) {
    throw new Error(
      `No shop serves (${LAT}, ${LNG}), so every discovery call would return an empty ` +
      'list and measure nothing. Set LAT/LNG to a point one of your shops delivers to.',
    );
  }
  return { shopsNearby: found };
}

export default function () {
  // The first thing a customer's app asks, before it has a shop at all.
  const discovery = http.get(
    `${BASE_URL}/api/marketplace/discovery?lat=${LAT}&lng=${LNG}`,
    { tags: { name: 'discovery' } },
  );
  discoveryDuration.add(discovery.timings.duration);
  record(discovery, true);
  check(discovery, { 'discovery 200 or shed 503': (r) => r.status === 200 || isCatalogShed(r) });

  sleep(1.5 + Math.random() * 2.5);

  // The older bare list, which released apps still call.
  const near = http.get(`${BASE_URL}/api/marketplace/shops?lat=${LAT}&lng=${LNG}`, {
    tags: { name: 'shops_near' },
  });
  record(near, true);

  sleep(1.5 + Math.random() * 2.5);

  // Opening one shop: the expensive read, because it computes that shop's
  // trading record and rating inside its own scope.
  const storefront = http.get(`${BASE_URL}/api/marketplace/shops/${SHOP_ID}`, {
    tags: { name: 'storefront' },
  });
  discoveryDuration.add(storefront.timings.duration);
  record(storefront, true);
  check(storefront, {
    'storefront 200/404 or shed 503': (r) =>
      r.status === 200 || r.status === 404 || isCatalogShed(r),
  });

  // Comparing one item across every shop in reach. Only when a variant was
  // named - guessing an id would measure a 404.
  if (VARIANT_ID && Math.random() < 0.3) {
    sleep(1.5 + Math.random() * 2.5);
    const compare = http.get(
      `${BASE_URL}/api/discovery/compare?variantId=${VARIANT_ID}&lat=${LAT}&lng=${LNG}`,
      { tags: { name: 'compare' } },
    );
    record(compare, true);
  }

  sleep(1.5 + Math.random() * 2.5);
}
