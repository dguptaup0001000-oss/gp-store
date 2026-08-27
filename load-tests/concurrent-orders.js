// 10 / 25 / 50 / 100 concurrent COD order workflows against a test target.
//
// Default BASE_URL is localhost. Do NOT point this at production: it places
// real COD orders. Use a local/staging backend with test accounts.
//
//   BASE_URL=http://127.0.0.1:8081/v1 VUS=10 HOLD_TIME=30s \
//     k6 run load-tests/concurrent-orders.js
//
// Integrity (duplicate / lost / negative stock) is asserted in
// ConcurrentOrderLoadTest against Postgres. This script measures HTTP
// latency and error rate of the same workflow.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:8081/v1').replace(/\/$/, '');
const VUS = Number(__ENV.VUS || 10);
const HOLD_TIME = __ENV.HOLD_TIME || '30s';

const ordersPlaced = new Counter('orders_placed');
const ordersFailed = new Counter('orders_failed');
const status5xx = new Counter('status_5xx');
const checkoutDuration = new Trend('checkout_workflow_duration', true);

const accounts = new SharedArray('accounts', () => {
  try {
    const raw = JSON.parse(open('./accounts.json'));
    return Array.isArray(raw) ? raw : (raw.accounts || []);
  } catch (e) {
    return [];
  }
});

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-vus',
      vus: VUS,
      duration: HOLD_TIME,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    status_5xx: ['count==0'],
    'http_req_duration{name:place_order}': ['p(95)<2000', 'p(99)<4000'],
  },
};

export function setup() {
  const health = http.get(`${BASE_URL}/api/health`);
  if (health.status !== 200) {
    throw new Error(`target ${BASE_URL} is not healthy: ${health.status}`);
  }
  return { started: Date.now() };
}

function authHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
    'Idempotency-Key': uuidv4(),
  };
}

export default function () {
  if (accounts.length === 0) {
    ordersFailed.add(1);
    sleep(1);
    return;
  }
  const account = accounts[__VU % accounts.length];
  const start = Date.now();
  const headers = authHeaders(account.accessToken || account.token);

  const categories = http.get(`${BASE_URL}/api/categories`, { tags: { name: 'categories' } });
  check(categories, { 'categories 200': (r) => r.status === 200 });
  if (categories.status >= 500) status5xx.add(1);

  const feed = http.get(`${BASE_URL}/api/products/feed`, { tags: { name: 'feed' } });
  check(feed, { 'feed 200': (r) => r.status === 200 });
  if (feed.status >= 500) status5xx.add(1);

  const variantId = account.variantId;
  const addressId = account.addressId;
  if (!variantId || !addressId) {
    ordersFailed.add(1);
    sleep(1);
    return;
  }

  const cart = http.post(
    `${BASE_URL}/api/cart/add`,
    JSON.stringify({ variantId, quantity: 1 }),
    { headers, tags: { name: 'cart_add' } },
  );
  if (cart.status >= 500) status5xx.add(1);

  const placed = http.post(
    `${BASE_URL}/api/orders/place`,
    JSON.stringify({ addressId, paymentMethod: 'COD' }),
    { headers, tags: { name: 'place_order' } },
  );
  checkoutDuration.add(Date.now() - start);
  if (placed.status >= 500) status5xx.add(1);
  if (placed.status === 200) {
    ordersPlaced.add(1);
  } else {
    ordersFailed.add(1);
  }
  sleep(1);
}
