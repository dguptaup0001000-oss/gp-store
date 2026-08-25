// k6 load test for GP-Store: a realistic customer, not a request cannon.
//
// Install k6: https://k6.io/docs/get-started/installation/
// Run: BASE_URL=http://localhost:8081/v1 k6 run browse-cart-checkout.js
//
// REQUIRES:
//   accounts.json  - seed-accounts.js (dedicated loadtest_vu_* identities)
//   fixtures.json  - snapshot-fixtures.js (known categories/products/variants)
//
// A virtual user is a shopper:
//   open app (health + store-info, once in setup)
//   → browse known categories / products (never random invalid IDs)
//   → search occasionally (~10%) with deterministic terms
//   → open a product
//   → cart VUs use ONE dedicated account each
//   → checkout uses a separate account pool (dirty-cart isolation)
//   → paced COD checkout (constant-arrival-rate, default 4 orders/min)
//
// Think time is 1.5–4s on EVERY path, including errors.
// Tokens are reused; refresh is used only on 401. Tokens are never logged.
// 502 aborts the test (kill switch). 503 catalog shed is expected protective
// behaviour. 429 is documented, not disabled.
//
// Do NOT raise BROWSE_VUS to 5,000 to "prove scale".

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

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

const http400 = new Counter('http_400');
const http401 = new Counter('http_401');
const http403 = new Counter('http_403');
const http404 = new Counter('http_404');
const http409 = new Counter('http_409');
const http429 = new Counter('http_429');
const http500 = new Counter('http_500');
const http502 = new Counter('http_502');
const http503 = new Counter('http_503');
const http504 = new Counter('http_504');

const catalogLatency = new Trend('catalog_latency', true);
const productLatency = new Trend('product_latency', true);
const searchLatency = new Trend('search_latency', true);
const cartLatency = new Trend('cart_latency', true);
const checkoutLatency = new Trend('checkout_latency', true);
const orderLatency = new Trend('order_latency', true);
const authLatency = new Trend('auth_latency', true);

const catalogErrors = new Counter('catalog_errors');
const productErrors = new Counter('product_errors');
const searchErrors = new Counter('search_errors');
const cartErrors = new Counter('cart_errors');
const checkoutErrors = new Counter('checkout_errors');
const orderErrors = new Counter('order_errors');
const authErrors = new Counter('auth_errors');

const errClient = new Counter('err_client');
const errAuth = new Counter('err_auth');
const errRateLimit = new Counter('err_rate_limit');
const errApplication = new Counter('err_application');
const errDatabase = new Counter('err_database');
const errRedis = new Counter('err_redis');
const errTimeout = new Counter('err_timeout');
const errConnection = new Counter('err_connection');
const errProxy502 = new Counter('err_proxy_502');
const errProxy503 = new Counter('err_proxy_503');
const errProxy504 = new Counter('err_proxy_504');
const errInfra = new Counter('err_infra_capacity');
const errTestData = new Counter('err_test_data');
const errUnknown = new Counter('err_unknown');
const errExpected503 = new Counter('err_expected_protective_503');
const errUnexpected503 = new Counter('err_unexpected_503');

const ordersPlaced = new Counter('orders_placed');
const ordersRateLimited = new Counter('orders_rate_limited');
const ordersRejected = new Counter('orders_rejected_client_error');
const ordersIdempotentOk = new Counter('orders_idempotent_ok');
const ordersDuplicate = new Counter('orders_duplicate_failure');
const retriesAttempted = new Counter('retries_attempted');
const retriesSkippedPermanent = new Counter('retries_skipped_permanent');

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

const FALLBACK_SEARCH_TERMS = ['rice', 'oil', 'soap', 'milk', 'atta', 'biscuit', 'tea', 'salt', 'sugar', 'dal'];

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

const fixturesFile = new SharedArray('fixtures', function () {
  try {
    return [JSON.parse(open('./fixtures.json'))];
  } catch (e) {
    return [];
  }
});

const tokenCache = {};

function fixtures() {
  return fixturesFile.length ? fixturesFile[0] : { products: [], categories: [], searchTerms: FALLBACK_SEARCH_TERMS };
}

function randomItem(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function thinkTime() {
  sleep(1.5 + Math.random() * 2.5);
}

function jsonBody(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

function isCatalogShed(res) {
  if (res.status !== 503) return false;
  const shedHeader = res.headers['X-GP-Shed'] || res.headers['x-gp-shed'];
  if (shedHeader) return true;
  const body = typeof res.body === 'string' ? res.body : '';
  return body.indexOf('POOL_SATURATED') >= 0 || body.indexOf('The shop is busy') >= 0;
}

function classify(res) {
  const s = res.status;
  const body = typeof res.body === 'string' ? res.body : '';
  const err = String(res.error || '');
  if (s === 0) {
    if (/timeout/i.test(err)) return 'TIMEOUT';
    return 'CONNECTION_ERROR';
  }
  if (s === 429) return 'RATE_LIMIT';
  if (s === 401 || s === 403) return 'AUTH_ERROR';
  if (s === 400 || s === 404 || s === 409 || s === 422) {
    if (/Inventory not found|is_test_data|test SKU|leftover/i.test(body)) return 'TEST_DATA_ERROR';
    return 'CLIENT_ERROR';
  }
  if (s === 502) {
    if (!body || /bad gateway|html/i.test(body)) return 'PROXY_502';
    return 'INFRASTRUCTURE_CAPACITY';
  }
  if (s === 503) {
    if (isCatalogShed(res)) return 'EXPECTED_PROTECTIVE_503';
    return 'UNEXPECTED_503';
  }
  if (s === 504) return 'PROXY_504';
  if (s >= 500) {
    if (/hikari|jdbc|postgres|PSQLException/i.test(body)) return 'DATABASE_ERROR';
    if (/redis|lettuce/i.test(body)) return 'REDIS_ERROR';
    return 'APPLICATION_ERROR';
  }
  return null;
}

function addClass(kind) {
  if (!kind) return;
  if (kind === 'CLIENT_ERROR') errClient.add(1);
  else if (kind === 'AUTH_ERROR') errAuth.add(1);
  else if (kind === 'RATE_LIMIT') errRateLimit.add(1);
  else if (kind === 'APPLICATION_ERROR') errApplication.add(1);
  else if (kind === 'DATABASE_ERROR') errDatabase.add(1);
  else if (kind === 'REDIS_ERROR') errRedis.add(1);
  else if (kind === 'TIMEOUT') errTimeout.add(1);
  else if (kind === 'CONNECTION_ERROR') errConnection.add(1);
  else if (kind === 'PROXY_502') errProxy502.add(1);
  else if (kind === 'PROXY_503') errProxy503.add(1);
  else if (kind === 'PROXY_504') errProxy504.add(1);
  else if (kind === 'INFRASTRUCTURE_CAPACITY') errInfra.add(1);
  else if (kind === 'TEST_DATA_ERROR') errTestData.add(1);
  else if (kind === 'EXPECTED_PROTECTIVE_503') errExpected503.add(1);
  else if (kind === 'UNEXPECTED_503') errUnexpected503.add(1);
  else errUnknown.add(1);
}

function endpointGroup(name) {
  if (name === 'categories' || name === 'browse_category') return 'catalog';
  if (name === 'product_detail') return 'product';
  if (name === 'search') return 'search';
  if (name === 'view_cart' || name === 'add_to_cart' || name === 'update_cart') return 'cart';
  if (name === 'checkout_preview') return 'checkout';
  if (name === 'place_order' || name === 'order_status' || name === 'order_history') return 'order';
  if (name === 'auth_refresh') return 'auth';
  return 'other';
}

function recordLatency(name, duration) {
  const group = endpointGroup(name);
  if (group === 'catalog') catalogLatency.add(duration);
  else if (group === 'product') productLatency.add(duration);
  else if (group === 'search') searchLatency.add(duration);
  else if (group === 'cart') cartLatency.add(duration);
  else if (group === 'checkout') checkoutLatency.add(duration);
  else if (group === 'order') orderLatency.add(duration);
  else if (group === 'auth') authLatency.add(duration);
}

function recordEndpointError(name) {
  const group = endpointGroup(name);
  if (group === 'catalog') catalogErrors.add(1);
  else if (group === 'product') productErrors.add(1);
  else if (group === 'search') searchErrors.add(1);
  else if (group === 'cart') cartErrors.add(1);
  else if (group === 'checkout') checkoutErrors.add(1);
  else if (group === 'order') orderErrors.add(1);
  else if (group === 'auth') authErrors.add(1);
}

function recordOutcome(res, name) {
  const s = res.status;
  const len = res.body ? res.body.length : 0;
  bytesReceived.add(len);
  payloadBytes.add(len);
  recordLatency(name, res.timings.duration);

  if (s === 400) http400.add(1);
  else if (s === 401) http401.add(1);
  else if (s === 403) http403.add(1);
  else if (s === 404) http404.add(1);
  else if (s === 409) http409.add(1);
  else if (s === 429) http429.add(1);
  else if (s === 500) http500.add(1);
  else if (s === 502) http502.add(1);
  else if (s === 503) http503.add(1);
  else if (s === 504) http504.add(1);

  const kind = classify(res);
  addClass(kind);
  const failed = s === 0 || s >= 400;
  if (failed && kind !== 'RATE_LIMIT' && kind !== 'EXPECTED_PROTECTIVE_503') {
    recordEndpointError(name);
  }

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
    exec.test.abort('KILL SWITCH: HTTP 502 — stop and investigate; do not retry');
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
    exec.test.abort('KILL SWITCH: HTTP 504 — proxy/upstream timeout');
    return;
  }
  if (s >= 500) {
    status5xx.add(1);
    return;
  }
  if (s >= 400) {
    status4xx.add(1);
    if (s === 401 || s === 403 || s === 404) {
      retriesSkippedPermanent.add(1);
    }
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

function liveAccount(base) {
  if (!base || !base.email) return null;
  if (!tokenCache[base.email]) {
    tokenCache[base.email] = {
      email: base.email,
      token: base.token,
      refreshToken: base.refreshToken,
      customerId: base.customerId,
      addressId: base.addressId,
      role: base.role,
    };
  }
  return tokenCache[base.email];
}

function refreshAccount(account) {
  if (!account || !account.refreshToken) return false;
  retriesAttempted.add(1);
  const res = http.post(
    `${BASE_URL}/api/auth/refresh`,
    JSON.stringify({ refreshToken: account.refreshToken }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'auth_refresh' } },
  );
  recordOutcome(res, 'auth_refresh');
  if (res.status !== 200) return false;
  const body = jsonBody(res);
  if (!body || !body.token) {
    authErrors.add(1);
    return false;
  }
  account.token = body.token;
  if (body.refreshToken) account.refreshToken = body.refreshToken;
  return true;
}

function authRequest(method, url, account, body, name, extraHeaders) {
  const params = {
    headers: Object.assign(
      {
        Authorization: `Bearer ${account.token}`,
        'Content-Type': 'application/json',
      },
      extraHeaders || {},
    ),
    tags: { name },
  };
  let res = http.request(method, url, body, params);
  recordOutcome(res, name);
  if (res.status === 401 && account.refreshToken) {
    if (refreshAccount(account)) {
      params.headers.Authorization = `Bearer ${account.token}`;
      res = http.request(method, url, body, params);
      recordOutcome(res, name);
    }
  }
  return res;
}

function checkoutIdempotencyKey(email) {
  const bytes = new Uint8Array(16);
  for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${email}-${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function splitAccounts(raw) {
  const vu = raw.filter((a) => a && a.token && a.addressId && a.role);
  const cart = vu.filter((a) => a.role === 'cart');
  const checkout = vu.filter((a) => a.role === 'checkout');
  if (cart.length && checkout.length) {
    return { cart, checkout };
  }
  const usable = raw.filter((a) => a && a.token && a.addressId);
  if (usable.length === 0) return { cart: [], checkout: [] };
  const checkoutN = Math.min(10, Math.max(1, Math.floor(usable.length / 16)));
  return {
    cart: usable.slice(0, Math.max(0, usable.length - checkoutN)),
    checkout: usable.slice(Math.max(0, usable.length - checkoutN)),
  };
}

export function setup() {
  const fx = fixtures();
  if (!fx.products || fx.products.length === 0) {
    throw new Error('fixtures.json missing shoppable products. Run snapshot-fixtures.js first.');
  }

  const health = http.get(`${BASE_URL}/api/health`, { tags: { name: 'liveness' } });
  recordOutcome(health, 'liveness');
  check(health, {
    'setup: liveness 200': (r) => r.status === 200,
    'setup: liveness body': (r) => String(r.body || '').length > 0,
  });

  const ready = http.get(`${BASE_URL}/api/health/ready`, { tags: { name: 'readiness' } });
  recordOutcome(ready, 'readiness');
  check(ready, { 'setup: readiness 200': (r) => r.status === 200 });

  const store = http.get(`${BASE_URL}/api/store-info`, { tags: { name: 'store_info' } });
  recordOutcome(store, 'store_info');

  const res = http.get(`${BASE_URL}/api/categories`, { tags: { name: 'categories' } });
  recordOutcome(res, 'categories');
  const cats = jsonBody(res);
  check(res, {
    'setup: categories 200': (r) => r.status === 200,
    'setup: categories array': () => Array.isArray(cats) && cats.length > 0,
    'setup: category has id+name': () => !!(cats && cats[0] && cats[0].id && cats[0].name),
  });
  if (!cats || cats.length === 0) {
    throw new Error('No categories returned from /api/categories');
  }

  const split = splitAccounts(accounts.slice());
  if (split.cart.length === 0) {
    console.warn('No cart accounts — cart scenario will no-op. Run seed-accounts.js.');
  }
  if (split.cart.length < CART_VUS) {
    console.warn(`cart accounts (${split.cart.length}) < CART_VUS (${CART_VUS}); some VUs will share identities.`);
  }

  return {
    fixtures: fx,
    categories: fx.categories && fx.categories.length ? fx.categories : cats,
    products: fx.products,
    searchTerms: fx.searchTerms && fx.searchTerms.length ? fx.searchTerms : FALLBACK_SEARCH_TERMS,
    cartAccounts: split.cart,
    checkoutAccounts: split.checkout,
  };
}

function rampingScenario(execName, vus) {
  const stages = [];
  if (WARMUP_VUS > 0 && WARMUP_TIME && WARMUP_TIME !== '0' && WARMUP_TIME !== '0s') {
    stages.push({ duration: WARMUP_TIME, target: WARMUP_VUS });
  }
  stages.push({ duration: RAMP_TIME, target: vus });
  stages.push({ duration: HOLD_TIME, target: vus });
  stages.push({ duration: RAMP_DOWN_TIME, target: 0 });
  return {
    executor: 'ramping-vus',
    exec: execName,
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

function pickCategory(data) {
  const list = data.categories || [];
  if (list.length === 0) return null;
  return list[(exec.vu.idInTest + exec.vu.iterationInInstance) % list.length];
}

function pickProduct(data) {
  const list = data.products || [];
  if (list.length === 0) return null;
  return list[(exec.vu.idInTest + exec.vu.iterationInInstance) % list.length];
}

function pickSearchTerm(data) {
  const list = data.searchTerms || FALLBACK_SEARCH_TERMS;
  return list[(exec.vu.idInTest + exec.vu.iterationInInstance) % list.length];
}

export function browse(data) {
  group('browse', function () {
    const catsRes = http.get(`${BASE_URL}/api/categories`, { tags: { name: 'categories' } });
    recordOutcome(catsRes, 'categories');
    const cats = jsonBody(catsRes);
    check(catsRes, {
      'categories: 200 or protective 503': (r) => r.status === 200 || isCatalogShed(r),
      'categories: valid JSON when 200': (r) => r.status !== 200 || (Array.isArray(cats) && cats.length > 0),
      'categories: structure when 200': (r) => r.status !== 200 || !!(cats && cats[0] && cats[0].id && cats[0].name),
    });
    thinkTime();
    if (catsRes.status !== 200) {
      return;
    }

    const category = pickCategory(data);
    if (!category) {
      thinkTime();
      return;
    }

    const catRes = http.get(`${BASE_URL}/api/products/category/${category.id}?page=0&size=20`, {
      tags: { name: 'browse_category' },
    });
    recordOutcome(catRes, 'browse_category');
    const page = jsonBody(catRes);
    check(catRes, {
      'browse category: 200 or protective 503': (r) => r.status === 200 || isCatalogShed(r),
      'browse category: page JSON': (r) => r.status !== 200 || !!(page && Array.isArray(page.content)),
    });
    thinkTime();
    if (catRes.status !== 200) {
      return;
    }

    if (Math.random() < 0.1) {
      const term = pickSearchTerm(data);
      const searchRes = http.get(
        `${BASE_URL}/api/products/search/instant?keyword=${encodeURIComponent(term)}&page=0&size=20`,
        { tags: { name: 'search' } },
      );
      recordOutcome(searchRes, 'search');
      const results = jsonBody(searchRes);
      check(searchRes, {
        'search: 200, 429, or protective 503': (r) => r.status === 200 || r.status === 429 || isCatalogShed(r),
        'search: results structure': (r) => r.status !== 200 || !!(results && Array.isArray(results.content)),
      });
      thinkTime();
    }

    const product = pickProduct(data);
    if (product) {
      const detailRes = http.get(`${BASE_URL}/api/products/${product.id}`, {
        tags: { name: 'product_detail' },
      });
      recordOutcome(detailRes, 'product_detail');
      const detail = jsonBody(detailRes);
      check(detailRes, {
        'product detail: 200 or protective 503': (r) => r.status === 200 || isCatalogShed(r),
        'product detail: required fields': (r) => r.status !== 200 || !!(detail && detail.id && detail.name),
      });
      thinkTime();
    }
  });
}

function accountForCart(data) {
  const pool = data.cartAccounts || [];
  if (pool.length === 0) return null;
  return liveAccount(pool[(exec.vu.idInInstance - 1) % pool.length]);
}

function accountForCheckout(data) {
  const pool = data.checkoutAccounts || [];
  if (pool.length === 0) return null;
  return liveAccount(pool[exec.scenario.iterationInTest % pool.length]);
}

export function cart(data) {
  const account = accountForCart(data);
  if (!account) {
    thinkTime();
    return;
  }
  const product = pickProduct(data);
  if (!product || !product.variantId) {
    thinkTime();
    return;
  }

  group('cart', function () {
    const addRes = authRequest(
      'POST',
      `${BASE_URL}/api/carts/add?variantId=${product.variantId}&quantity=1`,
      account,
      null,
      'add_to_cart',
    );
    check(addRes, {
      'add to cart: 200 or 429': (r) => r.status === 200 || r.status === 429,
      'add to cart: not 5xx/timeout': (r) => r.status !== 0 && r.status < 500,
    });
    thinkTime();
    if (addRes.status !== 200) {
      return;
    }

    const cartRes = authRequest('GET', `${BASE_URL}/api/carts/mine`, account, null, 'view_cart');
    const cartBody = jsonBody(cartRes);
    check(cartRes, {
      'view cart: 200': (r) => r.status === 200,
      'view cart: items array': () => !!(cartBody && Array.isArray(cartBody.items)),
      'view cart: totals': () => cartBody != null && cartBody.totalAmount != null && cartBody.totalItems != null,
    });

    const items = (cartBody && cartBody.items) || [];
    if (items.length > 0) {
      thinkTime();
      const item = items[0];
      const updateRes = authRequest(
        'PUT',
        `${BASE_URL}/api/carts/items/${item.cartItemId}?quantity=2`,
        account,
        null,
        'update_cart',
      );
      check(updateRes, { 'update cart quantity: 200 or 429': (r) => r.status === 200 || r.status === 429 });
    }
    thinkTime();
  });
}

function clearCart(account) {
  const existing = authRequest('GET', `${BASE_URL}/api/carts/mine`, account, null, 'view_cart');
  if (existing.status !== 200) return;
  const body = jsonBody(existing);
  const items = (body && body.items) || [];
  for (let i = 0; i < items.length; i++) {
    authRequest('DELETE', `${BASE_URL}/api/carts/items/${items[i].cartItemId}`, account, null, 'update_cart');
  }
}

export function checkout(data) {
  const account = accountForCheckout(data);
  const product = pickProduct(data);
  if (!account || !product || !product.variantId) {
    thinkTime();
    return;
  }

  group('checkout', function () {
    clearCart(account);

    const addRes = authRequest(
      'POST',
      `${BASE_URL}/api/carts/add?variantId=${product.variantId}&quantity=1`,
      account,
      null,
      'add_to_cart',
    );
    if (addRes.status !== 200) {
      thinkTime();
      return;
    }
    thinkTime();

    const previewRes = authRequest(
      'GET',
      `${BASE_URL}/api/orders/checkout-preview?addressId=${account.addressId}`,
      account,
      null,
      'checkout_preview',
    );
    const preview = jsonBody(previewRes);
    check(previewRes, {
      'checkout preview: 200': (r) => r.status === 200,
      'checkout preview: totals': () =>
        !!(preview && preview.estimatedTotal != null && preview.deliveryFee != null && preview.subtotal != null),
      'checkout preview: deliverable': () => !preview || preview.deliverable === true,
    });
    thinkTime();
    if (previewRes.status !== 200 || (preview && preview.deliverable === false)) {
      return;
    }

    const key = checkoutIdempotencyKey(account.email);
    const payload = JSON.stringify({ addressId: account.addressId, paymentMethod: 'COD' });
    const orderRes = authRequest(
      'POST',
      `${BASE_URL}/api/orders/place`,
      account,
      payload,
      'place_order',
      { 'Idempotency-Key': key },
    );
    const placed = jsonBody(orderRes);
    check(orderRes, {
      'place order: 200': (r) => r.status === 200,
      'place order: orderId': () => !!(placed && placed.orderId),
      'place order: not 5xx/timeout': (r) => r.status !== 0 && r.status < 500,
    });

    if (orderRes.status === 200 && placed && placed.orderId) {
      ordersPlaced.add(1);
      const replay = authRequest(
        'POST',
        `${BASE_URL}/api/orders/place`,
        account,
        payload,
        'place_order',
        { 'Idempotency-Key': key },
      );
      const replayed = jsonBody(replay);
      if (replay.status === 200 && replayed && replayed.orderId === placed.orderId) {
        ordersIdempotentOk.add(1);
      } else if (replay.status === 200 && replayed && replayed.orderId && replayed.orderId !== placed.orderId) {
        ordersDuplicate.add(1);
        errApplication.add(1);
        console.warn(`duplicate order for same idempotency key: ${placed.orderId} vs ${replayed.orderId}`);
      }

      thinkTime();
      const historyRes = authRequest(
        'GET',
        `${BASE_URL}/api/orders/my-orders?page=0&size=20`,
        account,
        null,
        'order_history',
      );
      const history = jsonBody(historyRes);
      check(historyRes, {
        'order history: 200': (r) => r.status === 200,
        'order history: page': () => !!(history && (Array.isArray(history.content) || Array.isArray(history))),
      });

      if (Math.random() < 0.3) {
        thinkTime();
        const statusRes = authRequest(
          'GET',
          `${BASE_URL}/api/orders/${placed.orderId}`,
          account,
          null,
          'order_status',
        );
        const statusBody = jsonBody(statusRes);
        check(statusRes, {
          'order status: 200': (r) => r.status === 200,
          'order status: id matches': () => !statusBody || statusBody.orderId === placed.orderId,
        });
      }
    } else if (orderRes.status === 429) {
      ordersRateLimited.add(1);
    } else if (orderRes.status >= 400 && orderRes.status < 500) {
      ordersRejected.add(1);
      console.warn(`place_order HTTP ${orderRes.status}: ${String(orderRes.body || '').slice(0, 220)}`);
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

function trendP95(data, name) {
  const values = metricValues(data, name);
  return values && values['p(95)'] != null ? values['p(95)'] : null;
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
    catalog_p95: trendP95(data, 'catalog_latency'),
    product_p95: trendP95(data, 'product_latency'),
    search_p95: trendP95(data, 'search_latency'),
    cart_p95: trendP95(data, 'cart_latency'),
    checkout_p95: trendP95(data, 'checkout_latency'),
    order_p95: trendP95(data, 'order_latency'),
    auth_p95: trendP95(data, 'auth_latency'),
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
    http_400: metricCount(data, 'http_400'),
    http_401: metricCount(data, 'http_401'),
    http_403: metricCount(data, 'http_403'),
    http_404: metricCount(data, 'http_404'),
    http_409: metricCount(data, 'http_409'),
    http_429: metricCount(data, 'http_429'),
    http_500: metricCount(data, 'http_500'),
    http_502: metricCount(data, 'http_502'),
    http_503: metricCount(data, 'http_503'),
    http_504: metricCount(data, 'http_504'),
    catalog_errors: metricCount(data, 'catalog_errors'),
    product_errors: metricCount(data, 'product_errors'),
    search_errors: metricCount(data, 'search_errors'),
    cart_errors: metricCount(data, 'cart_errors'),
    checkout_errors: metricCount(data, 'checkout_errors'),
    order_errors: metricCount(data, 'order_errors'),
    auth_errors: metricCount(data, 'auth_errors'),
    err_client: metricCount(data, 'err_client'),
    err_auth: metricCount(data, 'err_auth'),
    err_rate_limit: metricCount(data, 'err_rate_limit'),
    err_application: metricCount(data, 'err_application'),
    err_database: metricCount(data, 'err_database'),
    err_redis: metricCount(data, 'err_redis'),
    err_timeout: metricCount(data, 'err_timeout'),
    err_connection: metricCount(data, 'err_connection'),
    err_proxy_502: metricCount(data, 'err_proxy_502'),
    err_proxy_503: metricCount(data, 'err_proxy_503'),
    err_proxy_504: metricCount(data, 'err_proxy_504'),
    err_infra_capacity: metricCount(data, 'err_infra_capacity'),
    err_test_data: metricCount(data, 'err_test_data'),
    err_unknown: metricCount(data, 'err_unknown'),
    err_expected_protective_503: metricCount(data, 'err_expected_protective_503'),
    err_unexpected_503: metricCount(data, 'err_unexpected_503'),
    ordersPlaced: metricCount(data, 'orders_placed'),
    ordersRateLimited: metricCount(data, 'orders_rate_limited'),
    ordersRejected: metricCount(data, 'orders_rejected_client_error'),
    orders_idempotent_ok: metricCount(data, 'orders_idempotent_ok'),
    orders_duplicate_failure: metricCount(data, 'orders_duplicate_failure'),
    retries_attempted: metricCount(data, 'retries_attempted'),
    retries_skipped_permanent: metricCount(data, 'retries_skipped_permanent'),
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
