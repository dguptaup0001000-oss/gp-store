// Self-contained load test for running from a phone (Termux) - no k6, no
// separate seeding step, just Node's built-in fetch. k6 doesn't have a real
// Termux/Android package and its compiled binaries generally won't run
// under Termux without a full proot-distro Linux chroot - too much friction
// for a phone-only setup. This trades k6's proper metrics/reporting for
// something that just works with `pkg install nodejs` and one command.
//
// HONEST LIMITATION: a phone's CPU, battery-throttling, and mobile/WiFi
// network are real ceilings of their own. This will very likely hit ITS OWN
// limit (not the backend's) well before generating serious concurrent load -
// so a small number here isn't necessarily good news about backend capacity,
// and a bad number isn't necessarily bad news about it either. Treat this as
// a smoke test and a first read on latency/error rate, not a capacity
// verdict. For a test that isn't limited by the test device itself, this
// same repo's k6 script (browse-cart-checkout.js) needs to run from a real
// computer or a cloud VM - see load-tests/README.md.
//
// Usage (in Termux):
//   pkg update -y && pkg install -y nodejs
//   curl -o phone-loadtest.js https://raw.githubusercontent.com/dguptaup0001000-oss/gp-store/main/load-tests/phone-loadtest.js
//   BASE_URL=https://your-backend.onrender.com/v1 node phone-loadtest.js
//
// Tune with env vars: ACCOUNTS (default 15), DURATION_SEC (default 120),
// BROWSE_VUS (default 20), CART_VUS (default 6).

const { randomUUID } = require('node:crypto');

const BASE_URL = (process.env.BASE_URL || '').replace(/\/$/, '');
if (!BASE_URL) {
  console.error('Set BASE_URL, e.g. BASE_URL=https://your-backend.onrender.com/v1 node phone-loadtest.js');
  process.exit(1);
}
const ACCOUNT_COUNT = parseInt(process.env.ACCOUNTS || '15', 10);
const DURATION_SEC = parseInt(process.env.DURATION_SEC || '120', 10);
const BROWSE_VUS = parseInt(process.env.BROWSE_VUS || '20', 10);
const CART_VUS = parseInt(process.env.CART_VUS || '6', 10);

const STORE_LAT = parseFloat(process.env.STORE_LATITUDE || '28.6139');
const STORE_LNG = parseFloat(process.env.STORE_LONGITUDE || '77.2090');
const SEARCH_TERMS = ['rice', 'oil', 'soap', 'milk', 'atta', 'biscuit', 'tea', 'salt', 'sugar', 'dal'];

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
function jitter(center, maxKm) {
  const deg = maxKm / 111;
  return center + (Math.random() * 2 - 1) * deg * 0.5;
}
function randomItem(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
function thinkTime() {
  return sleep(1500 + Math.random() * 2500);
}

// --- metrics -----------------------------------------------------------
const stats = {}; // name -> { latencies: [], errors: 0, ok: 0 }
function record(name, ms, ok) {
  if (!stats[name]) stats[name] = { latencies: [], errors: 0, ok: 0 };
  stats[name].latencies.push(ms);
  if (ok) stats[name].ok++;
  else stats[name].errors++;
}
async function timed(name, fn) {
  const start = Date.now();
  try {
    const res = await fn();
    record(name, Date.now() - start, res.status < 500);
    return res;
  } catch (e) {
    record(name, Date.now() - start, false);
    return { status: 0, ok: false, json: async () => null };
  }
}
function percentile(arr, p) {
  if (arr.length === 0) return 0;
  const sorted = [...arr].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length));
  return sorted[idx];
}

// --- seeding (same logic as seed-accounts.js, inlined for a one-command flow) --
async function seedAccounts(count) {
  const accounts = [];
  for (let i = 0; i < count; i++) {
    const suffix = `${Date.now()}_${i}`;
    const email = `phonetest_${suffix}@example.com`;
    // Was deterministic (index-only), so re-running this script tried to
    // register the exact same 10 digits every time and got a legitimate
    // duplicate rejection from the DB on every account - never a backend
    // outage, just this script colliding with its own previous run.
    const phone = '9' + String(Date.now()).slice(-6) + String(i).padStart(3, '0');
    const password = 'LoadTest123!';
    try {
      const registerRes = await fetch(`${BASE_URL}/api/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: `Phone Test ${i}`, email, phone, password }),
      });
      if (!registerRes.ok) throw new Error(`register ${registerRes.status}`);
      const auth = await registerRes.json();

      const addressRes = await fetch(`${BASE_URL}/api/addresses`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${auth.token}` },
        body: JSON.stringify({
          fullName: `Phone Test ${i}`,
          mobileNumber: phone,
          houseNo: `${i} Test House`,
          area: 'Load Test Area',
          city: 'Test City',
          district: 'Test District',
          state: 'Test State',
          pincode: '110001',
          country: 'India',
          latitude: jitter(STORE_LAT, 3),
          longitude: jitter(STORE_LNG, 3),
          defaultAddress: true,
        }),
      });
      if (!addressRes.ok) throw new Error(`address ${addressRes.status}`);
      const address = await addressRes.json();

      accounts.push({ email, token: auth.token, addressId: address.id });
      process.stdout.write(`\rSeeding accounts: ${accounts.length}/${count}`);
    } catch (e) {
      console.error(`\naccount ${i} failed: ${e.message}`);
    }
    if (i < count - 1) await sleep(3500); // stay under the 20/60s register limit
  }
  console.log('');
  return accounts;
}

// --- load scenarios ------------------------------------------------------
async function browseLoop(categories, deadline) {
  while (Date.now() < deadline) {
    const category = randomItem(categories);
    const catRes = await timed('browse_category', () =>
      fetch(`${BASE_URL}/api/products/category/${category.id}?page=0&size=20`),
    );
    await thinkTime();

    if (Math.random() < 0.1) {
      await timed('search', () =>
        fetch(`${BASE_URL}/api/products/search/instant?keyword=${randomItem(SEARCH_TERMS)}&page=0&size=20`),
      );
      await thinkTime();
    }

    try {
      const page = await catRes.json?.();
      const products = (page && page.content) || [];
      if (products.length > 0) {
        const product = randomItem(products);
        await timed('product_detail', () => fetch(`${BASE_URL}/api/products/${product.id}`));
        await thinkTime();
      }
    } catch (e) {
      // already recorded by timed() above
    }
  }
}

async function cartLoop(categories, accounts, deadline) {
  if (accounts.length === 0) return;
  while (Date.now() < deadline) {
    const account = randomItem(accounts);
    const category = randomItem(categories);
    const catRes = await timed('browse_category', () =>
      fetch(`${BASE_URL}/api/products/category/${category.id}?page=0&size=20`),
    );
    let products = [];
    try {
      const page = await catRes.json?.();
      products = ((page && page.content) || []).filter((p) => (p.variants || []).some((v) => v.available));
    } catch (e) {
      /* recorded already */
    }
    if (products.length === 0) {
      await thinkTime();
      continue;
    }
    const product = randomItem(products);
    const variant = product.variants.find((v) => v.available);
    await thinkTime();

    await timed('add_to_cart', () =>
      fetch(`${BASE_URL}/api/carts/add?variantId=${variant.id}&quantity=1`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${account.token}` },
      }),
    );
    await thinkTime();
    await timed('view_cart', () =>
      fetch(`${BASE_URL}/api/carts/mine`, { headers: { Authorization: `Bearer ${account.token}` } }),
    );
  }
}

// One checkout attempt roughly every 15s total (not per VU) - stays under
// RateLimitFilter's 20/60s-per-customer cap on /orders/place. See README.md
// for why this test can't validate thousands of simultaneous checkouts.
async function checkoutLoop(categories, accounts, deadline) {
  if (accounts.length === 0) return;
  while (Date.now() < deadline) {
    const account = randomItem(accounts);
    const category = randomItem(categories);
    const catRes = await timed('browse_category', () =>
      fetch(`${BASE_URL}/api/products/category/${category.id}?page=0&size=20`),
    );
    let products = [];
    try {
      const page = await catRes.json?.();
      products = ((page && page.content) || []).filter((p) => (p.variants || []).some((v) => v.available));
    } catch (e) {
      /* recorded already */
    }
    if (products.length > 0) {
      const product = randomItem(products);
      const variant = product.variants.find((v) => v.available);
      const addRes = await timed('add_to_cart', () =>
        fetch(`${BASE_URL}/api/carts/add?variantId=${variant.id}&quantity=1`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${account.token}` },
        }),
      );
      if (addRes.status === 200) {
        await timed('place_order', () =>
          fetch(`${BASE_URL}/api/orders/place`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              Authorization: `Bearer ${account.token}`,
              'Idempotency-Key': `${account.email}-${randomUUID()}`,
            },
            body: JSON.stringify({ addressId: account.addressId, paymentMethod: 'COD' }),
          }),
        );
      }
    }
    await sleep(15000);
  }
}

async function main() {
  console.log(`GP-Store phone load test against ${BASE_URL}`);
  console.log(`(a phone's own CPU/network is the likely bottleneck here, not necessarily the backend - see this file's header)\n`);

  const catRes = await fetch(`${BASE_URL}/api/categories`);
  if (!catRes.ok) throw new Error(`Could not load categories (${catRes.status}) - check BASE_URL`);
  const categories = await catRes.json();
  if (!categories.length) throw new Error('No categories returned - is the catalog seeded?');

  const accounts = await seedAccounts(ACCOUNT_COUNT);
  if (accounts.length === 0) {
    console.warn('No accounts seeded - cart/checkout scenarios will be skipped, running browse-only.');
  }

  console.log(`\nRunning load for ${DURATION_SEC}s (${BROWSE_VUS} browse + ${CART_VUS} cart "virtual users", checkout throttled to ~1 attempt/8s)...`);
  const deadline = Date.now() + DURATION_SEC * 1000;

  const workers = [];
  for (let i = 0; i < BROWSE_VUS; i++) workers.push(browseLoop(categories, deadline));
  for (let i = 0; i < CART_VUS; i++) workers.push(cartLoop(categories, accounts, deadline));
  workers.push(checkoutLoop(categories, accounts, deadline));

  const progressTimer = setInterval(() => {
    const remaining = Math.max(0, Math.round((deadline - Date.now()) / 1000));
    process.stdout.write(`\r${remaining}s remaining...   `);
  }, 2000);

  await Promise.all(workers);
  clearInterval(progressTimer);

  console.log('\n\n--- Results ---');
  console.log('endpoint'.padEnd(18) + 'count'.padEnd(8) + 'errors'.padEnd(8) + 'p50 ms'.padEnd(9) + 'p95 ms'.padEnd(9) + 'p99 ms');
  for (const [name, s] of Object.entries(stats)) {
    const count = s.ok + s.errors;
    console.log(
      name.padEnd(18) +
        String(count).padEnd(8) +
        String(s.errors).padEnd(8) +
        String(percentile(s.latencies, 50)).padEnd(9) +
        String(percentile(s.latencies, 95)).padEnd(9) +
        String(percentile(s.latencies, 99)),
    );
  }
  console.log('\nCross-check this against Render\'s CPU/memory graph and Supabase\'s connection-count graph for this same time window - that combination shows what actually strained first.');
}

main().catch((err) => {
  console.error('\n', err);
  process.exit(1);
});
