// k6 load test for GP-Store: a realistic customer, not a request cannon.
//
// Install k6: https://k6.io/docs/get-started/installation/
// Run: BASE_URL=http://localhost:8081/v1 k6 run browse-cart-checkout.js
//
// REQUIRES accounts.json for cart/checkout - run seed-accounts.js first.
// Browse works without it.
//
// A virtual user is a shopper:
//   open app (health + store-info, once in setup)
//   → browse categories / products
//   → search occasionally (~10%, not 40%)
//   → open a product
//   → sometimes add to cart, view cart, bump quantity
//   → rarely check out (constant-arrival-rate, default 4 orders/min)
//   → sometimes refresh order status
//
// Think time is 1.5–4s on EVERY path, including errors. A tight loop on
// 5xx is how the previous run produced ~948k requests and 201 GB.
// Tokens are reused from accounts.json; the script never logs in per VU.
//
// Do NOT raise BROWSE_VUS to 5,000 to "prove scale". A previous 5,005-VU
// production run measured overload of one small Render instance
// (~40 Tomcat threads, 10 DB connections), not shopper capacity.

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const status2xx = new Counter('status_2xx');
const status3xx = new Counter('status_3xx');
const status4xx = new Counter('status_4xx');
const status429 = new Counter('status_429');
const status5xx = new Counter('status_5xx');
const status502 = new Counter('status_502');
const status503 = new Counter('status_503');
const status503Shed = new Counter('status_503_shed');
const status503Unexpected = new Counter('status_503_unexpected');
const status504 = new Counter('status_504');
const statusNetworkError = new Counter('status_network_error');
const bytesReceived = new Counter('response_bytes');
const payloadBytes = new Trend('payload_bytes', false);

const ordersPlaced = new Counter('orders_placed');
const ordersRateLimited = new Counter('orders_rate_limited');
const ordersRejected = new Counter('orders_rejected_client_error');

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8081/v1').replace(/\/$/, '');
const BROWSE_VUS = parseInt(__ENV.BROWSE_VUS || '50', 10);
const CART_VUS = parseInt(__ENV.CART_VUS || '15', 10);
const CHECKOUT_RATE = parseInt(__ENV.CHECKOUT_RATE || (CART_VUS > 0 ? '4' : '0'), 10);
const WARMUP_TIME = __ENV.WARMUP_TIME || '0s';
const WARMUP_VUS = parseInt(__ENV.WARMUP_VUS || '0', 10);
const RAMP_TIME = __ENV.RAMP_TIME || '1m';
const HOLD_TIME = __ENV.HOLD_TIME || '3m';
const RAMP_DOWN_TIME = __ENV.RAMP_DOWN_TIME || RAMP_TIME;
const SUMMARY_PATH = __ENV.SUMMARY_PATH || '';

const SEARCH_TERMS = ['rice', 'oil', 'soap', 'milk', 'atta', 'biscuit', 'tea', 'salt', 'sugar', 'dal'];

// Init-context: applies to every VU. 429 and catalog 503 are expected under
// pressure; 502 is not.
http.setResponseCallback(
  http.expectedStatuses({ min: 200, max: 399 }, 400, 401, 403, 404, 409, 429, 503),
);

const accounts = new SharedArray('accounts', function () {
  try {
    return JSON.parse(open('./accounts.json'));
  } catch (e) {
    return [];
  }
});

function randomItem(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

// Glance-and-tap, not a tight loop. 1.5–4s matches a shopper reading a card.
function thinkTime() {
  sleep(1.5 + Math.random() * 2.5);
}

function authHeaders(account) {
  return { headers: { Authorization: `Bearer ${account.token}`, 'Content-Type': 'application/json' } };
}

function checkoutIdempotencyKey(email) {
  const bytes = new Uint8Array(16);
  for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${email}-${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function isCatalogShed(res) {
  if (res.status !== 503) return false;
  const shedHeader = res.headers['X-GP-Shed'] || res.headers['x-gp-shed'];
  if (shedHeader) return true;
  const body = typeof res.body === 'string' ? res.body : '';
  return body.indexOf('POOL_SATURATED') >= 0 || body.indexOf('The shop is busy') >= 0;
}

function recordOutcome(res) {
  const s = res.status;
  const len = res.body ? res.body.length : 0;
  bytesReceived.add(len);
  payloadBytes.add(len);

  if (s === 0) {
    statusNetworkError.add(1);
    return;
  }
  if (s === 429) {
    status429.add(1);
    status4xx.add(1);
    return;
  }
  if (s === 502) {
    status502.add(1);
    status5xx.add(1);
    return;
  }
  if (s === 503) {
    status503.add(1);
    if (isCatalogShed(res)) {
      status503Shed.add(1);
    } else {
      status503Unexpected.add(1);
      status5xx.add(1);
    }
    return;
  }
  if (s === 504) {
    status504.add(1);
    status5xx.add(1);
    return;
  }
  if (s >= 500) {
    status5xx.add(1);
    return;
  }
  if (s >= 400) {
    status4xx.add(1);
    return;
  }
  if (s >= 300) {
    status3xx.add(1);
    return;
  }
  if (s >= 200) {
    status2xx.add(1);
  }
}

export function setup() {
  const health = http.get(`${BASE_URL}/api/health`, { tags: { name: 'liveness' } });
  recordOutcome(health);
  check(health, { 'setup: liveness 200': (r) => r.status === 200 });

  const ready = http.get(`${BASE_URL}/api/health/ready`, { tags: { name: 'readiness' } });
  recordOutcome(ready);
  check(ready, { 'setup: readiness 200': (r) => r.status === 200 });

  const store = http.get(`${BASE_URL}/api/store-info`, { tags: { name: 'store_info' } });
  recordOutcome(store);

  const res = http.get(`${BASE_URL}/api/categories`, { tags: { name: 'categories' } });
  recordOutcome(res);
  check(res, { 'setup: categories loaded': (r) => r.status === 200 });
  const categories = res.json();
  if (!categories || categories.length === 0) {
    throw new Error('No categories returned from /api/categories - is BASE_URL correct and the catalog seeded?');
  }
  if (accounts.length === 0) {
    console.warn('accounts.json is empty/missing - cart and checkout scenarios will no-op. Run seed-accounts.js first.');
  }
  return { categories, accounts: accounts.slice() };
}

function rampingScenario(exec, vus) {
  const stages = [];
  if (WARMUP_VUS > 0 && WARMUP_TIME && WARMUP_TIME !== '0' && WARMUP_TIME !== '0s') {
    stages.push({ duration: WARMUP_TIME, target: WARMUP_VUS });
  }
  stages.push({ duration: RAMP_TIME, target: vus });
  stages.push({ duration: HOLD_TIME, target: vus });
  stages.push({ duration: RAMP_DOWN_TIME, target: 0 });
  return {
    executor: 'ramping-vus',
    exec,
    startVUs: 0,
    stages,
  };
}

const scenarios = {};

if (BROWSE_VUS > 0) {
  scenarios.browse = rampingScenario('browse', BROWSE_VUS);
}

if (CART_VUS > 0) {
  scenarios.cart = rampingScenario('cart', CART_VUS);
}

if (CHECKOUT_RATE > 0) {
  const checkout = {
    executor: 'constant-arrival-rate',
    exec: 'checkout',
    // Modest on purpose. Real checkouts are rare relative to browse.
    // RateLimitFilter caps POST /orders/place at 20/min per customer.
    rate: CHECKOUT_RATE,
    timeUnit: '1m',
    duration: HOLD_TIME,
    preAllocatedVUs: Math.min(5, Math.max(1, CHECKOUT_RATE)),
    maxVUs: Math.min(10, Math.max(2, CHECKOUT_RATE * 2)),
  };
  if (__ENV.CHECKOUT_START && __ENV.CHECKOUT_START !== '0s') {
    checkout.startTime = __ENV.CHECKOUT_START;
  }
  scenarios.checkout = checkout;
}

if (Object.keys(scenarios).length === 0) {
  throw new Error('No scenarios enabled - set BROWSE_VUS, CART_VUS, and/or CHECKOUT_RATE above 0.');
}

export const options = {
  scenarios,
  thresholds: {
    // Reads are tighter than writes. One global p95 hid checkout behind browse.
    'http_req_duration{name:browse_category}': ['p(95)<1500'],
    'http_req_duration{name:categories}': ['p(95)<1000'],
    'http_req_duration{name:search}': ['p(95)<2000'],
    'http_req_duration{name:product_detail}': ['p(95)<2000'],
    'http_req_duration{name:view_cart}': ['p(95)<1500'],
    'http_req_duration{name:add_to_cart}': ['p(95)<2000'],
    'http_req_duration{name:checkout_preview}': ['p(95)<3000'],
    'http_req_duration{name:place_order}': ['p(95)<4000', 'p(99)<8000'],
    'http_req_duration{name:order_status}': ['p(95)<1500'],
    'status_502': [{ threshold: 'count==0', abortOnFail: true }],
    'status_503_unexpected': ['count==0'],
    'status_network_error': ['count==0'],
  },
};

export function browse(data) {
  group('browse', function () {
    const catsRes = http.get(`${BASE_URL}/api/categories`, { tags: { name: 'categories' } });
    recordOutcome(catsRes);
    check(catsRes, { 'categories: 200 or shed 503': (r) => r.status === 200 || isCatalogShed(r) });
    thinkTime();
    if (catsRes.status !== 200) {
      return;
    }

    const category = randomItem(data.categories);

    const catRes = http.get(`${BASE_URL}/api/products/category/${category.id}?page=0&size=20`, {
      tags: { name: 'browse_category' },
    });
    recordOutcome(catRes);
    check(catRes, { 'browse category: 200 or shed 503': (r) => r.status === 200 || isCatalogShed(r) });
    thinkTime();
    if (catRes.status !== 200) {
      return;
    }

    if (Math.random() < 0.1) {
      const term = randomItem(SEARCH_TERMS);
      const searchRes = http.get(
        `${BASE_URL}/api/products/search/instant?keyword=${term}&page=0&size=20`,
        { tags: { name: 'search' } },
      );
      recordOutcome(searchRes);
      check(searchRes, {
        'search: 200, 429, or shed 503': (r) => r.status === 200 || r.status === 429 || isCatalogShed(r),
      });
      thinkTime();
    }

    try {
      const page = catRes.json();
      const products = (page && page.content) || [];
      if (products.length > 0) {
        const product = randomItem(products);
        const detailRes = http.get(`${BASE_URL}/api/products/${product.id}`, {
          tags: { name: 'product_detail' },
        });
        recordOutcome(detailRes);
        check(detailRes, {
          'product detail: 200 or shed 503': (r) => r.status === 200 || isCatalogShed(r),
        });
        thinkTime();
      }
    } catch (e) {
      thinkTime();
    }
  });
}

function accountPool(data) {
  if (data && data.accounts && data.accounts.length > 0) return data.accounts;
  return accounts;
}

export function cart(data) {
  const pool = accountPool(data);
  if (pool.length === 0) {
    thinkTime();
    return;
  }
  const account = randomItem(pool);

  group('cart', function () {
    const category = randomItem(data.categories);
    const catRes = http.get(`${BASE_URL}/api/products/category/${category.id}?page=0&size=20`, {
      tags: { name: 'browse_category' },
    });
    recordOutcome(catRes);
    if (catRes.status !== 200) {
      thinkTime();
      return;
    }

    const page = catRes.json();
    const products = ((page && page.content) || []).filter((p) => (p.variants || []).some((v) => v.available));
    if (products.length === 0) {
      thinkTime();
      return;
    }

    const product = randomItem(products);
    const variant = product.variants.find((v) => v.available);
    thinkTime();

    const addRes = http.post(
      `${BASE_URL}/api/carts/add?variantId=${variant.id}&quantity=1`,
      null,
      { ...authHeaders(account), tags: { name: 'add_to_cart' } },
    );
    recordOutcome(addRes);
    check(addRes, { 'add to cart: 200 or 429': (r) => r.status === 200 || r.status === 429 });
    thinkTime();
    if (addRes.status !== 200) {
      return;
    }

    const cartRes = http.get(`${BASE_URL}/api/carts/mine`, {
      ...authHeaders(account),
      tags: { name: 'view_cart' },
    });
    recordOutcome(cartRes);
    check(cartRes, { 'view cart: 200': (r) => r.status === 200 });

    try {
      const cartBody = cartRes.json();
      const items = (cartBody && cartBody.items) || [];
      if (items.length > 0) {
        thinkTime();
        const item = randomItem(items);
        const updateRes = http.put(
          `${BASE_URL}/api/carts/items/${item.cartItemId}?quantity=2`,
          null,
          { ...authHeaders(account), tags: { name: 'update_cart' } },
        );
        recordOutcome(updateRes);
        check(updateRes, { 'update cart quantity: 200 or 429': (r) => r.status === 200 || r.status === 429 });
      }
    } catch (e) {
      // Cart body shape mismatch would already have failed the check above.
    }
    thinkTime();
  });
}

export function checkout(data) {
  const pool = accountPool(data);
  if (pool.length === 0) {
    thinkTime();
    return;
  }
  const account = randomItem(pool);

  group('checkout', function () {
    const category = randomItem(data.categories);
    const catRes = http.get(`${BASE_URL}/api/products/category/${category.id}?page=0&size=20`, {
      tags: { name: 'browse_category' },
    });
    recordOutcome(catRes);
    if (catRes.status !== 200) {
      thinkTime();
      return;
    }

    const page = catRes.json();
    const products = ((page && page.content) || []).filter((p) => (p.variants || []).some((v) => v.available));
    if (products.length === 0) {
      thinkTime();
      return;
    }

    const product = randomItem(products);
    const variant = product.variants.find((v) => v.available);

    const addRes = http.post(
      `${BASE_URL}/api/carts/add?variantId=${variant.id}&quantity=1`,
      null,
      { ...authHeaders(account), tags: { name: 'add_to_cart' } },
    );
    recordOutcome(addRes);
    if (addRes.status !== 200) {
      thinkTime();
      return;
    }
    thinkTime();

    const previewRes = http.get(
      `${BASE_URL}/api/orders/checkout-preview?addressId=${account.addressId}`,
      { ...authHeaders(account), tags: { name: 'checkout_preview' } },
    );
    recordOutcome(previewRes);
    check(previewRes, { 'checkout preview: 200 or 4xx': (r) => r.status === 200 || (r.status >= 400 && r.status < 500) });
    thinkTime();

    const orderRes = http.post(
      `${BASE_URL}/api/orders/place`,
      JSON.stringify({ addressId: account.addressId, paymentMethod: 'COD' }),
      {
        headers: { ...authHeaders(account).headers, 'Idempotency-Key': checkoutIdempotencyKey(account.email) },
        tags: { name: 'place_order' },
      },
    );
    recordOutcome(orderRes);
    check(orderRes, {
      'place order: 200 or expected 4xx (empty cart / rate limit)': (r) =>
        r.status === 200 || r.status === 400 || r.status === 409 || r.status === 429,
      'place order: not a 5xx': (r) => r.status < 500,
    });

    if (orderRes.status === 200) {
      ordersPlaced.add(1);
      thinkTime();
      const historyRes = http.get(`${BASE_URL}/api/orders/my-orders?page=0&size=20`, {
        ...authHeaders(account),
        tags: { name: 'order_history' },
      });
      recordOutcome(historyRes);
      check(historyRes, { 'order history: 200': (r) => r.status === 200 });

      // Occasional status refresh - not a poll loop.
      if (Math.random() < 0.3) {
        try {
          const placed = orderRes.json();
          if (placed && placed.orderId) {
            thinkTime();
            const statusRes = http.get(`${BASE_URL}/api/orders/${placed.orderId}`, {
              ...authHeaders(account),
              tags: { name: 'order_status' },
            });
            recordOutcome(statusRes);
            check(statusRes, { 'order status: 200': (r) => r.status === 200 });
          }
        } catch (e) {
          // ignore parse errors; already counted
        }
      }
    } else if (orderRes.status === 429) {
      ordersRateLimited.add(1);
    } else if (orderRes.status >= 400 && orderRes.status < 500) {
      ordersRejected.add(1);
    }
    thinkTime();
  });
}

function metricValues(data, name) {
  const metric = data.metrics[name];
  return metric && metric.values ? metric.values : null;
}

function metricCount(data, name) {
  const values = metricValues(data, name);
  if (!values) return 0;
  if (typeof values.count === 'number') return values.count;
  if (typeof values.value === 'number') return values.value;
  return 0;
}

export function handleSummary(data) {
  const duration = metricValues(data, 'http_req_duration') || {};
  const summary = {
    generatedAt: new Date().toISOString(),
    baseUrl: BASE_URL,
    browseVus: BROWSE_VUS,
    cartVus: CART_VUS,
    checkoutRate: CHECKOUT_RATE,
    warmupTime: WARMUP_TIME,
    rampTime: RAMP_TIME,
    holdTime: HOLD_TIME,
    rampDownTime: RAMP_DOWN_TIME,
    httpReqs: metricCount(data, 'http_reqs'),
    rps: metricValues(data, 'http_reqs') ? metricValues(data, 'http_reqs').rate : 0,
    iterations: metricCount(data, 'iterations'),
    droppedIterations: metricCount(data, 'dropped_iterations'),
    vusMax: metricValues(data, 'vus_max') ? metricValues(data, 'vus_max').max : null,
    checks: metricValues(data, 'checks'),
    httpReqFailed: metricValues(data, 'http_req_failed'),
    p50: duration['p(50)'] ?? null,
    p90: duration['p(90)'] ?? null,
    p95: duration['p(95)'] ?? null,
    p99: duration['p(99)'] ?? null,
    maxLatency: duration.max ?? null,
    dataReceived: metricValues(data, 'data_received'),
    dataSent: metricValues(data, 'data_sent'),
    responseBytes: metricCount(data, 'response_bytes'),
    status_2xx: metricCount(data, 'status_2xx'),
    status_3xx: metricCount(data, 'status_3xx'),
    status_4xx: metricCount(data, 'status_4xx'),
    status_429: metricCount(data, 'status_429'),
    status_5xx: metricCount(data, 'status_5xx'),
    status_502: metricCount(data, 'status_502'),
    status_503: metricCount(data, 'status_503'),
    status_503_shed: metricCount(data, 'status_503_shed'),
    status_503_unexpected: metricCount(data, 'status_503_unexpected'),
    status_504: metricCount(data, 'status_504'),
    status_network_error: metricCount(data, 'status_network_error'),
    ordersPlaced: metricCount(data, 'orders_placed'),
    ordersRateLimited: metricCount(data, 'orders_rate_limited'),
    ordersRejected: metricCount(data, 'orders_rejected_client_error'),
    thresholds: Object.fromEntries(
      Object.entries(data.metrics || {})
        .filter(([, metric]) => metric.thresholds)
        .map(([name, metric]) => [name, metric.thresholds]),
    ),
  };

  const files = {};
  const body = `${JSON.stringify(summary, null, 2)}\n`;
  files.stdout = body;
  if (SUMMARY_PATH) {
    files[SUMMARY_PATH] = body;
  }
  return files;
}
