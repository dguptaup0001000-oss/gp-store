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
// Deterministic identities: loadtest_vu_0000@example.com … so a re-run
// logs in instead of creating a new customer. Never production emails.
// Never sends SMS: registration is email+password, and OTP_SMS_SENDING_ENABLED
// must stay false on the test instance.
//
// Usage:
//   BASE_URL=http://localhost:8081/v1 COUNT=160 node seed-accounts.js
//
// COUNT is the number of dedicated VU accounts (cart + checkout). Existing
// timestamp-suffixed accounts are kept but not counted toward COUNT.
// Takes roughly (missing accounts) * 3.5 seconds.

import fs from 'node:fs';

const BASE_URL = (process.env.BASE_URL || 'http://localhost:8081/v1').replace(/\/$/, '');
const COUNT = parseInt(process.env.COUNT || '160', 10);
const CART_COUNT = parseInt(process.env.CART_COUNT || String(Math.max(0, COUNT - 10)), 10);
const OUT_FILE = process.env.OUT_FILE || new URL('./accounts.json', import.meta.url);
const PASSWORD = 'LoadTest123!';

// Matches application.properties store.latitude/longitude (Malhia, UP) and
// store.max-delivery-radius-km (20). The previous Delhi pin (28.6139, 77.2090)
// sat ~700 km outside the shop's radius, so every seeded checkout failed
// deliverability even when the backend was healthy.
const STORE_LAT = parseFloat(process.env.STORE_LATITUDE || '27.162310');
const STORE_LNG = parseFloat(process.env.STORE_LONGITUDE || '83.940468');

function jitter(center, maxKm) {
  const deg = maxKm / 111;
  return center + (Math.random() * 2 - 1) * deg * 0.5;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function vuEmail(index) {
  return `loadtest_vu_${String(index).padStart(4, '0')}@example.com`;
}

function vuPhone(index) {
  // 10-digit, 8-prefix, unique per VU index. Not a real subscriber.
  return '8' + String(index).padStart(9, '0');
}

function vuRole(index) {
  return index < CART_COUNT ? 'cart' : 'checkout';
}

function readExisting() {
  try {
    const parsed = JSON.parse(fs.readFileSync(OUT_FILE, 'utf8'));
    return Array.isArray(parsed) ? parsed : [];
  } catch (e) {
    return [];
  }
}

function isVuAccount(account) {
  return typeof account.email === 'string' && /^loadtest_vu_\d+@example\.com$/.test(account.email);
}

function vuIndexFromEmail(email) {
  const m = /^loadtest_vu_(\d+)@example\.com$/.exec(email);
  return m ? parseInt(m[1], 10) : -1;
}

async function registerOrLogin(index) {
  const email = vuEmail(index);
  const phone = vuPhone(index);
  const role = vuRole(index);
  const name = `Load Test VU ${index}`;

  const registerRes = await fetch(`${BASE_URL}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, email, phone, password: PASSWORD }),
  });

  let auth;
  if (registerRes.ok) {
    auth = await registerRes.json();
  } else if (registerRes.status === 400 || registerRes.status === 409) {
    // Already created on a previous seed. Login reuses the same customer.
    const loginRes = await fetch(`${BASE_URL}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password: PASSWORD }),
    });
    if (!loginRes.ok) {
      throw new Error(`login failed (${loginRes.status}): ${await loginRes.text()}`);
    }
    auth = await loginRes.json();
  } else {
    throw new Error(`register failed (${registerRes.status}): ${await registerRes.text()}`);
  }

  const addressRes = await fetch(`${BASE_URL}/api/addresses`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${auth.token}`,
    },
    body: JSON.stringify({
      fullName: name,
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
    // Duplicate default address on re-seed: reuse the existing default.
    const listRes = await fetch(`${BASE_URL}/api/addresses/mine`, {
      headers: { Authorization: `Bearer ${auth.token}` },
    });
    if (!listRes.ok) {
      throw new Error(`address creation failed (${addressRes.status}): ${await addressRes.text()}`);
    }
    const list = await listRes.json();
    const addresses = Array.isArray(list) ? list : [];
    const existing = addresses.find((a) => a.defaultAddress) || addresses[0];
    if (!existing || !existing.id) {
      throw new Error(`address creation failed (${addressRes.status}) and no existing address`);
    }
    return {
      email,
      password: PASSWORD,
      token: auth.token,
      refreshToken: auth.refreshToken,
      customerId: auth.customerId,
      addressId: existing.id,
      role,
      vuIndex: index,
    };
  }
  const address = await addressRes.json();

  return {
    email,
    password: PASSWORD,
    token: auth.token,
    refreshToken: auth.refreshToken,
    customerId: auth.customerId,
    addressId: address.id,
    role,
    vuIndex: index,
  };
}

async function main() {
  if (COUNT < 1) {
    throw new Error('COUNT must be >= 1');
  }
  const existing = readExisting();
  const leftover = existing.filter((a) => !isVuAccount(a));
  const byIndex = new Map();
  for (const account of existing.filter(isVuAccount)) {
    byIndex.set(vuIndexFromEmail(account.email), account);
  }

  const missing = [];
  for (let i = 0; i < COUNT; i++) {
    if (!byIndex.has(i)) missing.push(i);
  }

  console.log(
    `Seeding VU accounts against ${BASE_URL}: have ${byIndex.size}/${COUNT}, ` +
      `need ${missing.length} (~${Math.ceil((missing.length * 3.5) / 60)} min). ` +
      `cart=${CART_COUNT} checkout=${Math.max(0, COUNT - CART_COUNT)}. ` +
      `Leftover non-VU test accounts kept: ${leftover.length}.`,
  );

  for (let n = 0; n < missing.length; n++) {
    const i = missing[n];
    try {
      const account = await registerOrLogin(i);
      byIndex.set(i, account);
      process.stdout.write(`\r${byIndex.size}/${COUNT} VU accounts ready (last index ${i})`);
    } catch (err) {
      console.error(`\naccount ${i} failed: ${err.message}`);
    }
    if (n < missing.length - 1) await sleep(3500);
  }

  const vuAccounts = [];
  for (let i = 0; i < COUNT; i++) {
    if (byIndex.has(i)) vuAccounts.push(byIndex.get(i));
  }
  const all = leftover.concat(vuAccounts);
  fs.writeFileSync(OUT_FILE, JSON.stringify(all, null, 2));
  console.log(`\nWrote ${vuAccounts.length} VU accounts (+ ${leftover.length} leftover) to ${OUT_FILE}`);
  if (vuAccounts.length < COUNT) {
    process.exitCode = 1;
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
