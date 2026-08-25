// One-time setup script for the k6 load test in this directory - NOT a k6
// script itself, just plain Node (needs Node 18+ for global fetch).
//
// WHY THIS EXISTS: /api/auth/register and /api/auth/login are both rate
// limited to 20 requests/60s PER SOURCE IP (see RateLimitFilter.java) - that
// limit is correct and should stay on in production, but it also means a k6
// run firing thousands of virtual users from one machine (one IP) can never
// register/log in more than 20 accounts a minute no matter how it's written.
// So account creation happens once, slowly, ahead of time here - the actual
// load test then reuses these pre-issued tokens and never calls
// register/login itself, which is both realistic (real users don't re-login
// every request either) and the only way to reach real concurrency on the
// endpoints that AREN'T rate limited the same way (browse, cart).
//
// Usage:
//   BASE_URL=https://your-backend.onrender.com/v1 COUNT=50 node seed-accounts.js
//
// Takes roughly COUNT * 3.5 seconds to run (paced to stay under the 20/60s
// register limit) - for 50 accounts that's ~3 minutes. Writes accounts.json
// in this directory; browse-cart-checkout.js reads that file directly.

const BASE_URL = (process.env.BASE_URL || 'http://localhost:8081/v1').replace(/\/$/, '');
const COUNT = parseInt(process.env.COUNT || '50', 10);
const OUT_FILE = process.env.OUT_FILE || new URL('./accounts.json', import.meta.url);

// Matches application.properties' store.latitude/longitude defaults
// (28.6139, 77.2090) and store.max-delivery-radius-km default (8km) - jitter
// stays well inside that radius so checkout's deliverability check passes.
// If your Render deployment overrides STORE_LATITUDE/STORE_LONGITUDE, update
// these two constants to match, or every seeded account's checkout will fail
// with "outside delivery radius".
const STORE_LAT = parseFloat(process.env.STORE_LATITUDE || '28.6139');
const STORE_LNG = parseFloat(process.env.STORE_LONGITUDE || '77.2090');

function jitter(center, maxKm) {
  // ~1 degree latitude is ~111km - keep well inside the radius, not at its edge.
  const deg = maxKm / 111;
  return center + (Math.random() * 2 - 1) * deg * 0.5;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function registerOne(index) {
  const suffix = `${Date.now()}_${index}`;
  const email = `loadtest_${suffix}@example.com`;
  // Was deterministic (index-only), so every re-run tried to register the
  // exact same 10 digits as the last run and got a legitimate duplicate
  // rejection from the DB - this was never a backend outage, just this
  // script colliding with its own previous run. Date.now() makes each run
  // distinct; index keeps accounts distinct within a single run.
  const phone = '9' + String(Date.now()).slice(-6) + String(index).padStart(3, '0');
  const password = 'LoadTest123!';

  const registerRes = await fetch(`${BASE_URL}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: `Load Test ${index}`, email, phone, password }),
  });
  if (!registerRes.ok) {
    throw new Error(`register failed (${registerRes.status}): ${await registerRes.text()}`);
  }
  const auth = await registerRes.json();

  const addressRes = await fetch(`${BASE_URL}/api/addresses`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${auth.token}`,
    },
    body: JSON.stringify({
      fullName: `Load Test ${index}`,
      mobileNumber: phone,
      houseNo: `${index} Test House`,
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
  if (!addressRes.ok) {
    throw new Error(`address creation failed (${addressRes.status}): ${await addressRes.text()}`);
  }
  const address = await addressRes.json();

  return {
    email,
    password,
    token: auth.token,
    refreshToken: auth.refreshToken,
    customerId: auth.customerId,
    addressId: address.id,
  };
}

async function main() {
  console.log(`Seeding ${COUNT} accounts against ${BASE_URL} (~${Math.ceil(COUNT * 3.5 / 60)} min)...`);
  const accounts = [];
  for (let i = 0; i < COUNT; i++) {
    try {
      const account = await registerOne(i);
      accounts.push(account);
      process.stdout.write(`\r${accounts.length}/${COUNT} accounts seeded`);
    } catch (err) {
      console.error(`\naccount ${i} failed: ${err.message}`);
    }
    // 3.5s between registrations stays under the 20-per-60s AUTH limit
    // (~17/min) even with clock drift.
    if (i < COUNT - 1) await sleep(3500);
  }
  await import('node:fs').then((fs) => fs.writeFileSync(OUT_FILE, JSON.stringify(accounts, null, 2)));
  console.log(`\nWrote ${accounts.length} accounts to ${OUT_FILE}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
