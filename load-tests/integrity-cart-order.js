// Correctness probes — not a VU flood.
//
// 1. Cart concurrency: two overlapping quantity writes on the same line.
//    The API's intended semantic is last-write-wins on quantity (absolute
//    PUT), not a lost row or a negative quantity.
// 2. Order idempotency: the same Idempotency-Key must return the same
//    orderId. A load test that "passes" while inserting duplicate orders
//    is a failure.
//
// Uses one dedicated checkout account from accounts.json. COD only.
//
//   BASE_URL=http://localhost:8081/v1 node integrity-cart-order.js

import fs from 'node:fs';

const BASE_URL = (process.env.BASE_URL || 'http://localhost:8081/v1').replace(/\/$/, '');
const ACCOUNTS_FILE = new URL('./accounts.json', import.meta.url);
const FIXTURES_FILE = new URL('./fixtures.json', import.meta.url);

function loadJson(url) {
  return JSON.parse(fs.readFileSync(url, 'utf8'));
}

function pickAccount(accounts) {
  const checkout = accounts.filter((a) => a.role === 'checkout');
  const pool = checkout.length ? checkout : accounts.filter((a) => a.token && a.addressId);
  if (pool.length === 0) {
    throw new Error('No accounts with token+addressId. Run seed-accounts.js.');
  }
  return pool[pool.length - 1];
}

async function api(method, path, account, body, extraHeaders) {
  const headers = {
    Authorization: `Bearer ${account.token}`,
    'Content-Type': 'application/json',
    ...(extraHeaders || {}),
  };
  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body == null ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch (e) {
    json = null;
  }
  return { status: res.status, json, text: text.slice(0, 400) };
}

async function clearCart(account) {
  const cart = await api('GET', '/api/carts/mine', account);
  if (cart.status !== 200 || !cart.json) return;
  for (const item of cart.json.items || []) {
    await api('DELETE', `/api/carts/items/${item.cartItemId}`, account);
  }
}

async function main() {
  const accounts = loadJson(ACCOUNTS_FILE);
  const fixtures = loadJson(FIXTURES_FILE);
  const account = pickAccount(accounts);
  const product = (fixtures.products || [])[0];
  if (!product || !product.variantId) {
    throw new Error('fixtures.json has no shoppable variant. Run snapshot-fixtures.js.');
  }

  const report = { cartConcurrency: null, orderIdempotency: null };

  await clearCart(account);
  const add = await api('POST', `/api/carts/add?variantId=${product.variantId}&quantity=1`, account);
  if (add.status !== 200) {
    throw new Error(`add to cart failed ${add.status}: ${add.text}`);
  }
  const mine = await api('GET', '/api/carts/mine', account);
  const item = (mine.json && mine.json.items && mine.json.items[0]) || null;
  if (!item) {
    throw new Error('cart empty after add');
  }

  const [first, second] = await Promise.all([
    api('PUT', `/api/carts/items/${item.cartItemId}?quantity=2`, account),
    api('PUT', `/api/carts/items/${item.cartItemId}?quantity=3`, account),
  ]);
  const after = await api('GET', '/api/carts/mine', account);
  const line = (after.json && after.json.items || []).find((i) => i.cartItemId === item.cartItemId);
  const qty = line ? line.quantity : null;
  const okRace = after.status === 200 && line && (qty === 2 || qty === 3) && qty > 0
    && (after.json.items || []).filter((i) => i.variantId === product.variantId).length === 1;
  report.cartConcurrency = {
    putStatuses: [first.status, second.status],
    finalQuantity: qty,
    duplicateRows: (after.json && after.json.items || []).filter((i) => i.variantId === product.variantId).length,
    ok: okRace,
    semantic: 'last-write-wins absolute quantity; one row; never negative',
  };

  await clearCart(account);
  const add2 = await api('POST', `/api/carts/add?variantId=${product.variantId}&quantity=1`, account);
  if (add2.status !== 200) {
    throw new Error(`checkout add failed ${add2.status}: ${add2.text}`);
  }
  const key = `${account.email}-integrity-${Date.now()}-aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee`;
  const payload = { addressId: account.addressId, paymentMethod: 'COD' };
  const place1 = await api('POST', '/api/orders/place', account, payload, { 'Idempotency-Key': key });
  const place2 = await api('POST', '/api/orders/place', account, payload, { 'Idempotency-Key': key });
  const id1 = place1.json && (place1.json.orderId || place1.json.id);
  const id2 = place2.json && (place2.json.orderId || place2.json.id);
  report.orderIdempotency = {
    firstStatus: place1.status,
    secondStatus: place2.status,
    firstOrderId: id1 || null,
    secondOrderId: id2 || null,
    sameOrder: id1 != null && id1 === id2,
    ok: place1.status === 200 && id1 != null && id1 === id2,
  };

  console.log(JSON.stringify(report, null, 2));
  if (!report.cartConcurrency.ok || !report.orderIdempotency.ok) {
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
