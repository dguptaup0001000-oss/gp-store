// k6 load test for GP-Store: browse -> add to cart -> checkout.
//
// Install k6: https://k6.io/docs/get-started/installation/
// Run: BASE_URL=https://your-backend.onrender.com/v1 k6 run browse-cart-checkout.js
//
// REQUIRES accounts.json in this directory first - run seed-accounts.js to
// generate it (see that file's header for why login can't happen inline
// here). Only the browse scenario works without it.
//
// THREE SEPARATE SCENARIOS, weighted to resemble real traffic and to respect
// what this backend deliberately rate-limits:
//   - browse   : anonymous, GET-only (categories, category browsing, search,
//                product detail) - no auth, not rate limited, this is where
//                most concurrent traffic in a real shopping app actually
//                sits at any given instant. This is the scenario that can
//                legitimately be pushed to real scale from one machine.
//   - cart     : authenticated (pre-seeded token), browse + add to cart +
//                view cart - not rate limited either, so this can also run
//                at real concurrency.
//   - checkout : authenticated, calls POST /orders/place - deliberately
//                capped at a low constant-arrival-rate (see comment below)
//                because RateLimitFilter caps this endpoint at 10
//                requests/60s PER SOURCE IP. Every VU in this test shares
//                this machine's one IP, so this scenario can never validate
//                "N thousand simultaneous checkouts" no matter how it's
//                written - that's a real, current architectural limit worth
//                knowing about on its own (see load-tests/README.md), not a
//                bug in this script.
//
// Tune VU counts via env vars: BROWSE_VUS, CART_VUS (defaults below are a
// conservative starting point - see README.md for what's safe to run
// against production right now vs. what needs a staging environment first).

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

// Custom counters so the end-of-run summary reports actual order-placement
// outcomes, not just HTTP pass/fail - "how many orders actually got placed
// this run" is what a duplicate-order check against the DB afterward needs
// as its baseline (k6 itself has no DB access to verify duplicates directly
// - see load-tests/README.md's "what this can't validate yet" section).
const ordersPlaced = new Counter('orders_placed');
const ordersRateLimited = new Counter('orders_rate_limited');
const ordersRejected = new Counter('orders_rejected_client_error');

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8081/v1').replace(/\/$/, '');
const BROWSE_VUS = parseInt(__ENV.BROWSE_VUS || '50', 10);
const CART_VUS = parseInt(__ENV.CART_VUS || '15', 10);
const RAMP_TIME = __ENV.RAMP_TIME || '1m';
const HOLD_TIME = __ENV.HOLD_TIME || '3m';

const SEARCH_TERMS = ['rice', 'oil', 'soap', 'milk', 'atta', 'biscuit', 'tea', 'salt', 'sugar', 'dal'];

const accounts = new SharedArray('accounts', function () {
  try {
    return JSON.parse(open('./accounts.json'));
  } catch (e) {
    return []; // browse scenario doesn't need these - only warn if cart/checkout actually run
  }
});

function randomItem(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

// Real users pause to read/decide between actions - a tight request loop
// with no pacing measures something closer to a stress test than realistic
// concurrent-user load. 1-3s matches a quick "glance and tap" pace.
function thinkTime() {
  sleep(1 + Math.random() * 2);
}

function authHeaders(account) {
  return { headers: { Authorization: `Bearer ${account.token}`, 'Content-Type': 'application/json' } };
}

export function setup() {
  const res = http.get(`${BASE_URL}/api/categories`);
  check(res, { 'setup: categories loaded': (r) => r.status === 200 });
  const categories = res.json();
  if (!categories || categories.length === 0) {
    throw new Error('No categories returned from /api/categories - is BASE_URL correct and the catalog seeded?');
  }
  if (accounts.length === 0) {
    console.warn('accounts.json is empty/missing - cart and checkout scenarios will fail. Run seed-accounts.js first.');
  }
  return { categories };
}

/**
 * Scenarios are assembled rather than declared as one literal, because a
 * scenario set to zero VUs must be OMITTED, not included with a target of 0.
 *
 * k6 rejects the whole script - before sending a single request - with
 * "scenario cart has configuration errors: either startVUs or one of the
 * stages' target values must be greater than 0". So a browse-only run,
 * which is the normal shape of a high-VU run because cart and checkout
 * need seeded accounts, would fail at startup and measure nothing at all.
 * The run still burns its full setup time first, so the failure looks like
 * a load test that ran and died rather than one that never started.
 *
 * Zero therefore means "leave this scenario out", which is what anyone
 * setting it to zero intends.
 */
function rampingScenario(exec, vus) {
  return {
    executor: 'ramping-vus',
    exec,
    startVUs: 0,
    stages: [
      { duration: RAMP_TIME, target: vus },
      { duration: HOLD_TIME, target: vus },
      { duration: RAMP_TIME, target: 0 },
    ],
  };
}

const scenarios = {};

if (BROWSE_VUS > 0) {
  scenarios.browse = rampingScenario('browse', BROWSE_VUS);
}

if (CART_VUS > 0) {
  scenarios.cart = rampingScenario('cart', CART_VUS);
}

// Checkout rides on the seeded accounts the cart scenario needs, so it is
// tied to the same switch: with no accounts there is nothing to check out
// with, and every iteration would fail on authentication and report as a
// backend failure when it is really a test-setup gap.
if (CART_VUS > 0) {
  scenarios.checkout = {
      executor: 'constant-arrival-rate',
      exec: 'checkout',
      // Paced against the backend's checkout rate limit, which CHANGED:
      // POST /orders/place is now limited per CUSTOMER (default 20/min,
      // rate-limit.checkout-per-minute), not per IP as it was when this
      // number was chosen. Per-IP was the binding constraint from a single
      // generator; per-customer is not, because the script rotates through
      // seeded accounts.
      //
      // Still deliberately modest: the goal of the checkout scenario is to
      // prove orders are placed correctly under concurrent load, not to
      // maximise order throughput. Raising it mostly produces 429s from the
      // per-customer limit once the account pool is small relative to the
      // rate, which is a property of the test setup rather than a finding
      // about the backend. Raise the seeded account count first if you want
      // a higher checkout rate.
      rate: 8,
      timeUnit: '1m',
      duration: HOLD_TIME,
      preAllocatedVUs: 5,
      maxVUs: 10,
  };
}

if (Object.keys(scenarios).length === 0) {
  // Better to say so than to let k6 start with nothing to do and report a
  // clean run that tested absolutely nothing.
  throw new Error('No scenarios enabled - set BROWSE_VUS and/or CART_VUS above 0.');
}

export const options = {
  scenarios,
  thresholds: {
    // Aspirational, not pass/fail gates for this exercise - the point of
    // Phase 2 is to find the REAL numbers, not assert ones in advance. Kept
    // loose so the run always completes and reports actuals either way.
    http_req_duration: ['p(95)<3000'],
    http_req_failed: ['rate<0.05'],
  },
};

export function browse(data) {
  group('browse', function () {
    const category = randomItem(data.categories);

    const catRes = http.get(`${BASE_URL}/api/products/category/${category.id}?page=0&size=20`, { tags: { name: 'browse_category' } });
    check(catRes, { 'browse category: 200': (r) => r.status === 200 });
    thinkTime();

    if (Math.random() < 0.4) {
      const term = randomItem(SEARCH_TERMS);
      const searchRes = http.get(`${BASE_URL}/api/products/search/instant?keyword=${term}&page=0&size=20`, { tags: { name: 'search' } });
      check(searchRes, { 'search: 200': (r) => r.status === 200 });
      thinkTime();
    }

    try {
      const page = catRes.json();
      const products = (page && page.content) || [];
      if (products.length > 0) {
        const product = randomItem(products);
        const detailRes = http.get(`${BASE_URL}/api/products/${product.id}`, { tags: { name: 'product_detail' } });
        check(detailRes, { 'product detail: 200': (r) => r.status === 200 });
        thinkTime();
      }
    } catch (e) {
      // Malformed page body would already have failed the check above -
      // nothing further to do for this iteration.
    }
  });
}

export function cart(data) {
  if (accounts.length === 0) return;
  const account = randomItem(accounts);

  group('cart', function () {
    const category = randomItem(data.categories);
    const catRes = http.get(`${BASE_URL}/api/products/category/${category.id}?page=0&size=20`, { tags: { name: 'browse_category' } });
    if (catRes.status !== 200) return;

    const page = catRes.json();
    const products = ((page && page.content) || []).filter((p) => (p.variants || []).some((v) => v.available));
    if (products.length === 0) return;

    const product = randomItem(products);
    const variant = product.variants.find((v) => v.available);
    thinkTime();

    const addRes = http.post(
      `${BASE_URL}/api/carts/add?variantId=${variant.id}&quantity=1`,
      null,
      authHeaders(account),
    );
    check(addRes, { 'add to cart: 200': (r) => r.status === 200 });
    thinkTime();

    const cartRes = http.get(`${BASE_URL}/api/carts/mine`, authHeaders(account));
    check(cartRes, { 'view cart: 200': (r) => r.status === 200 });

    // Real users often bump quantity up/down before settling - a stepper
    // tap, not just a one-shot add.
    try {
      const cartBody = cartRes.json();
      const items = (cartBody && cartBody.items) || [];
      if (items.length > 0) {
        thinkTime();
        const item = randomItem(items);
        const updateRes = http.put(
          `${BASE_URL}/api/carts/items/${item.cartItemId}?quantity=2`,
          null,
          authHeaders(account),
        );
        check(updateRes, { 'update cart quantity: 200': (r) => r.status === 200 });
      }
    } catch (e) {
      // Cart body shape mismatch would already have failed the check above.
    }
  });
}

export function checkout(data) {
  if (accounts.length === 0) return;
  const account = randomItem(accounts);

  group('checkout', function () {
    const category = randomItem(data.categories);
    const catRes = http.get(`${BASE_URL}/api/products/category/${category.id}?page=0&size=20`, { tags: { name: 'browse_category' } });
    if (catRes.status !== 200) return;

    const page = catRes.json();
    const products = ((page && page.content) || []).filter((p) => (p.variants || []).some((v) => v.available));
    if (products.length === 0) return;

    const product = randomItem(products);
    const variant = product.variants.find((v) => v.available);

    const addRes = http.post(
      `${BASE_URL}/api/carts/add?variantId=${variant.id}&quantity=1`,
      null,
      authHeaders(account),
    );
    if (addRes.status !== 200) return;
    thinkTime();

    // Real checkout always shows a cost preview before the customer commits
    // - not part of what's being placed, but part of the flow.
    const previewRes = http.get(
      `${BASE_URL}/api/orders/checkout-preview?addressId=${account.addressId}`,
      authHeaders(account),
    );
    check(previewRes, { 'checkout preview: 200': (r) => r.status === 200 });
    thinkTime();

    const orderRes = http.post(
      `${BASE_URL}/api/orders/place`,
      JSON.stringify({ addressId: account.addressId, paymentMethod: 'COD' }),
      { headers: { ...authHeaders(account).headers, 'Idempotency-Key': `${account.email}-${Date.now()}` } },
    );
    check(orderRes, {
      'place order: 200 or expected 4xx (empty cart / rate limit)': (r) => r.status === 200 || r.status === 400 || r.status === 429,
      'place order: not a 5xx': (r) => r.status < 500,
    });

    if (orderRes.status === 200) {
      ordersPlaced.add(1);
      thinkTime();
      // Realistic flow completion: a customer who just checked out looks at
      // their order history next, not nothing - see the flow this scenario
      // is meant to mirror (browse -> cart -> checkout -> order history).
      const historyRes = http.get(`${BASE_URL}/api/orders/my-orders?page=0&size=20`, authHeaders(account));
      check(historyRes, { 'order history: 200': (r) => r.status === 200 });
    } else if (orderRes.status === 429) {
      ordersRateLimited.add(1);
    } else if (orderRes.status >= 400 && orderRes.status < 500) {
      ordersRejected.add(1);
    }
  });
}
