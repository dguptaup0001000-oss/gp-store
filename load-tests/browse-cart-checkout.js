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

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      exec: 'browse',
      startVUs: 0,
      stages: [
        { duration: RAMP_TIME, target: BROWSE_VUS },
        { duration: HOLD_TIME, target: BROWSE_VUS },
        { duration: RAMP_TIME, target: 0 },
      ],
    },
    cart: {
      executor: 'ramping-vus',
      exec: 'cart',
      startVUs: 0,
      stages: [
        { duration: RAMP_TIME, target: CART_VUS },
        { duration: HOLD_TIME, target: CART_VUS },
        { duration: RAMP_TIME, target: 0 },
      ],
    },
    checkout: {
      executor: 'constant-arrival-rate',
      exec: 'checkout',
      // Deliberately under the 10/60s-per-IP limit on POST /orders/place -
      // see the file header. Raising this above ~8 just produces 429s, not
      // more signal.
      rate: 8,
      timeUnit: '1m',
      duration: HOLD_TIME,
      preAllocatedVUs: 5,
      maxVUs: 10,
    },
  },
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

    const orderRes = http.post(
      `${BASE_URL}/api/orders/place`,
      JSON.stringify({ addressId: account.addressId, paymentMethod: 'COD' }),
      { headers: { ...authHeaders(account).headers, 'Idempotency-Key': `${account.email}-${Date.now()}` } },
    );
    check(orderRes, {
      'place order: 200 or expected 4xx (empty cart / rate limit)': (r) => r.status === 200 || r.status === 400 || r.status === 429,
      'place order: not a 5xx': (r) => r.status < 500,
    });
  });
}
