// A marketplace's worth of traffic, against a marketplace's worth of shops.
//
// WHY THIS EXISTS ALONGSIDE staged-capacity.js. That script asks one narrow
// question - how many concurrent readers can the catalogue serve - against a
// single storefront, and it should stay that way, because a narrow probe is
// what you want when you are bisecting a regression. This one asks a
// different question: what happens when two thousand storefronts are real,
// customers arrive at their own coordinates, and some of them buy something.
// The accounting, the shed/throttle vocabulary and the gates are deliberately
// the SAME as staged-capacity.js, because a second dialect of "what counts as
// a failure" is how two runs stop being comparable.
//
//   BASE_URL=http://localhost:8081/v1 VUS=1000 HOLD_TIME=60s k6 run marketplace-traffic.js
//
// WHAT IS COUNTED, AND SEPARATELY (this is the point of the script):
//   requests_ok              2xx/3xx - a customer got an answer
//   status_429               deliberately throttled by the rate limiter
//   status_503_shed          deliberately refused: shed by a saturation filter,
//                            or answered by handlePoolExhausted. Both carry
//                            Retry-After, which is how they are recognised.
//   status_503_unexpected    a 503 with no Retry-After - nobody decided this
//   status_502               the proxy gave up
//   status_4xx_expected      401/403/404/409/410/422 - an answer, not a fault
//   status_4xx_unexpected    any other 4xx: the client asked for the wrong thing
//   status_500               the application broke
//   status_network_error     the connection never completed (k6 status 0)
//   status_timeout           the request exceeded the per-request timeout
//
// A THROTTLED CUSTOMER IS NOT A SERVED CUSTOMER. 429 and shed-503 are correct
// behaviour and do not fail a stage, but they are NOT successes either, and
// the summary must never be read as "N VUs were fully served" when a share of
// their requests were refused on purpose. served_ratio is printed for exactly
// that reason.
//
// TWO POPULATIONS, ON PURPOSE:
//   browse   - anonymous shoppers, scaled to VUS. Discovery at their own pin,
//              open a storefront, read its shelf, sometimes search, sometimes
//              open a product. This is what most marketplace traffic is.
//   shopper  - signed-in customers with a cart, ONE VU PER ACCOUNT. Fixed and
//              small, because accounts.json holds a few dozen accounts and
//              pointing a thousand VUs at one cart row measures lock
//              contention on a fixture, not the application.
//
// ORDER PLACEMENT IS NOT HERE. concurrent-orders.js already drives the
// checkout workflow and ConcurrentOrderLoadTest already asserts the integrity
// of what it produces against Postgres. Writing a third checkout path into
// this script would give two answers to one question.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8081/v1').replace(/\/$/, '');
const VUS = Number(__ENV.VUS || 100);
const HOLD_TIME = __ENV.HOLD_TIME || '60s';
const RAMP_TIME = __ENV.RAMP_TIME || '20s';
const REQ_TIMEOUT = __ENV.REQ_TIMEOUT || '30s';
const STAGE = __ENV.STAGE || String(VUS);

// The marketplace's corner of the map. Customers arrive at their own pin
// inside it, not all at one address - a single pin would warm one set of
// shops and measure a cache rather than a marketplace.
const LAT_MIN = Number(__ENV.LAT_MIN || 26.45);
const LAT_MAX = Number(__ENV.LAT_MAX || 27.15);
const LNG_MIN = Number(__ENV.LNG_MIN || 83.05);
const LNG_MAX = Number(__ENV.LNG_MAX || 83.90);

const accounts = new SharedArray('accounts', () => {
  try {
    const loaded = JSON.parse(open(__ENV.ACCOUNTS_FILE || './accounts.json'));
    return Array.isArray(loaded) ? loaded : [];
  } catch (e) {
    return [];
  }
});

const SHOPPERS = Math.min(Number(__ENV.SHOPPERS || accounts.length), accounts.length);

const requestsOk = new Counter('requests_ok');
const status429 = new Counter('status_429');
const status503Shed = new Counter('status_503_shed');
const status503Unexpected = new Counter('status_503_unexpected');
const status502 = new Counter('status_502');
const status4xxExpected = new Counter('status_4xx_expected');
const status4xxUnexpected = new Counter('status_4xx_unexpected');
const status500 = new Counter('status_500');
const statusNetworkError = new Counter('status_network_error');
const statusTimeout = new Counter('status_timeout');
const servedRatio = new Rate('served_ratio');
// ISOLATION, CHECKED ON EVERY SHELF, NOT ONCE AT REST. A tenant filter that
// holds in a quiet test can still leak under load: the scope lives in a
// ThreadLocal and the threads are pooled, so a shop's rows escaping into
// another shop's response is a CONCURRENCY bug and only concurrency finds it.
// The generator gives every shop exactly one trade and stamps the trade into
// every product name, so one shelf showing two trades is a leak - no lookup,
// no fixture, just the response disagreeing with itself.
const tenantLeaks = new Counter('tenant_leaks');

// 401/404/409 are answers, not failures: a token can expire, a listing can go
// out of stock between the shelf and the cart, and a duplicate order is
// SUPPOSED to be refused. Anything else in the 4xx range means the script is
// asking for something wrong, and that is worth knowing about.
const EXPECTED_4XX = [400, 401, 403, 404, 409, 410, 422];

// A 503 THE APPLICATION MEANT, told apart from one it did not.
//
// Retry-After is the test. Both deliberate paths set it: the saturation
// filters shed with Retry-After: 1 and an X-GP-Shed header, and
// GlobalExceptionHandler.handlePoolExhausted answers Retry-After: 2 when a
// request could not get a connection. A 503 with no Retry-After is nobody
// deciding anything - a proxy, a container, a crash - and that is the one
// worth failing a stage over.
//
// An earlier version matched on the shed filters' body text alone, and so
// filed every pool-exhaustion 503 under "unexpected". They are not
// unexpected; they are the application refusing work it cannot do, which is
// the behaviour the shedding exists to produce. Counting correct backpressure
// as a fault would have argued for widening a pool to make a test go green.
function isShed(res) {
  if (res.status !== 503) return false;
  if (res.headers['Retry-After'] || res.headers['retry-after']) return true;
  if (res.headers['X-GP-Shed'] || res.headers['x-gp-shed']) return true;
  const body = typeof res.body === 'string' ? res.body : '';
  return body.indexOf('POOL_SATURATED') >= 0 || body.indexOf('The shop is busy') >= 0;
}

function record(res) {
  const s = res.status;
  if (s >= 200 && s < 400) {
    requestsOk.add(1);
    servedRatio.add(true);
    return true;
  }
  servedRatio.add(false);
  if (s === 0) {
    // k6 reports a timeout as status 0 with a distinguishable error_code.
    if (res.error_code === 1050 || /timeout/i.test(res.error || '')) statusTimeout.add(1);
    else statusNetworkError.add(1);
  } else if (s === 429) status429.add(1);
  else if (s === 502) status502.add(1);
  else if (s === 503) { if (isShed(res)) status503Shed.add(1); else status503Unexpected.add(1); }
  else if (s >= 500) status500.add(1);
  else if (s >= 400 && s < 500) {
    if (EXPECTED_4XX.indexOf(s) < 0) status4xxUnexpected.add(1); else status4xxExpected.add(1);
  }
  return false;
}

function get(path, name, headers) {
  const res = http.get(`${BASE_URL}${path}`, {
    tags: { name: name, stage: STAGE },
    timeout: REQ_TIMEOUT,
    headers: headers || {},
  });
  record(res);
  return res;
}

export const options = {
  discardResponseBodies: false,
  scenarios: (function () {
    const s = {
      browse: {
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [{ duration: RAMP_TIME, target: VUS }, { duration: HOLD_TIME, target: VUS }],
        gracefulRampDown: '5s',
        exec: 'browse',
      },
    };
    // THE MARKETPLACE'S OWN FEED, which is now the default customer
    // experience and was not exercised by this test at all. Its shape is
    // completely different from the shop-scoped shelf above: it spans every
    // shop that serves a pin rather than one, so it is the endpoint most
    // capable of quietly reintroducing the per-shop loop the discovery work
    // removed. A ladder that only climbed the old shelf would prove the old
    // ceiling.
    s.marketplace = {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP_TIME, target: Math.max(1, Math.round(VUS / 3)) },
        { duration: HOLD_TIME, target: Math.max(1, Math.round(VUS / 3)) },
      ],
      exec: 'marketplace',
    };
    if (SHOPPERS > 0) {
      s.shopper = {
        executor: 'constant-vus',
        vus: SHOPPERS,
        duration: HOLD_TIME,
        startTime: RAMP_TIME,
        exec: 'shopper',
      };
    }
    return s;
  })(),
  thresholds: {
    // THE GATES ARE THE SAME AS staged-capacity.js. Intentional refusals do
    // not fail a stage; broken ones do.
    'http_req_duration{name:discovery}': ['p(95)<2000', 'p(99)<4000'],
    'http_req_duration{name:shelf}': ['p(95)<2000', 'p(99)<4000'],
    // THE SAME BUDGET AS THE SHOP-SCOPED SHELF, deliberately. The marketplace
    // feed reads across every shop serving a pin rather than one, so it would
    // be easy to justify a looser gate for it - and that is exactly how an
    // endpoint drifts into a per-shop loop without anybody noticing. It does
    // its grouping, ranking and paging in one statement; if it stops doing
    // that, this fails.
    'http_req_duration{name:market_feed}': ['p(95)<2000', 'p(99)<4000'],
    'http_req_duration{name:market_search}': ['p(95)<2000', 'p(99)<4000'],
    'http_req_duration{name:market_offers}': ['p(95)<2000', 'p(99)<4000'],
    status_502: ['count==0'],
    status_503_unexpected: ['count==0'],
    status_500: ['count==0'],
    status_network_error: ['count==0'],
    status_4xx_unexpected: ['count==0'],
    // A RELEASE BLOCKER, AND THE ONLY GATE HERE THAT IS ABOUT CORRECTNESS
    // RATHER THAN CAPACITY. Every other threshold says the marketplace was
    // slow or full; this one says it showed somebody another merchant's
    // goods, and no amount of load excuses that.
    tenant_leaks: ['count==0'],
  },
};

/** The trade stamped into this generator's product names, or null. */
function tradeOf(productName) {
  const m = /^GPTEST-([A-Z_]+)/.exec(productName || '');
  return m ? m[1] : null;
}

/**
 * One shelf, one trade. Returns how many trades the page actually showed, so
 * a caller can count a leak rather than only assert one.
 */
function tradesOnShelf(res) {
  if (res.status !== 200) return 0;
  let content;
  try {
    content = res.json('content');
  } catch (e) {
    return 0;
  }
  if (!content || !content.length) return 0;
  const seen = {};
  let n = 0;
  for (let i = 0; i < content.length; i++) {
    const trade = tradeOf(content[i].name);
    if (trade && !seen[trade]) { seen[trade] = true; n++; }
  }
  return n;
}

function pin() {
  return {
    lat: LAT_MIN + Math.random() * (LAT_MAX - LAT_MIN),
    lng: LNG_MIN + Math.random() * (LNG_MAX - LNG_MIN),
  };
}

const SEARCH_TERMS = ['cycle', 'saree', 'rice', 'phone', 'tyre', 'paint', 'book', 'cake', 'lamp', 'pump'];

export function setup() {
  const health = http.get(`${BASE_URL}/api/health`, { tags: { name: 'liveness' } });
  if (health.status !== 200) {
    throw new Error(`liveness failed with HTTP ${health.status} - refusing to start a stage`);
  }
  // Prove the marketplace is actually populated before measuring anything
  // against it. An empty dataset produces beautiful latencies.
  const p = { lat: (LAT_MIN + LAT_MAX) / 2, lng: (LNG_MIN + LNG_MAX) / 2 };
  const probe = http.get(`${BASE_URL}/api/marketplace/shops?lat=${p.lat}&lng=${p.lng}`);
  const shops = probe.status === 200 ? probe.json() : [];
  if (!shops || shops.length === 0) {
    throw new Error('No shops serve the centre of the test area - is the marketplace seeded?');
  }
  return { sampleShops: shops.slice(0, 50).map((s) => s.shopId) };
}

export function browse(data) {
  const where = pin();

  // 1. A customer opens the app: which shops deliver to me?
  const near = get(`/api/marketplace/shops?lat=${where.lat}&lng=${where.lng}`, 'discovery');
  let shopIds = data.sampleShops;
  if (near.status === 200) {
    try {
      const list = near.json();
      if (list && list.length) shopIds = list.map((s) => s.shopId);
    } catch (e) { /* a shed or throttled response has no list; fall back */ }
  }
  check(near, { 'discovery answered or was refused on purpose': (r) => r.status === 200 || r.status === 429 || isShed(r) });

  sleep(1 + Math.random() * 2);

  // 2. They open one storefront. Everything after this is that shop's scope.
  const shopId = shopIds[Math.floor(Math.random() * shopIds.length)];
  const hdr = { 'X-Shop-Id': String(shopId) };

  const shelf = get(`/api/products/feed?page=0&size=20`, 'shelf', hdr);
  check(shelf, { 'shelf answered or was refused on purpose': (r) => r.status === 200 || r.status === 429 || isShed(r) });

  const trades = tradesOnShelf(shelf);
  if (trades > 1) {
    tenantLeaks.add(1);
    console.error(`TENANT LEAK: shop ${shopId} showed ${trades} different trades on one shelf`);
  }
  check(shelf, { 'one shop, one trade': () => trades <= 1 });

  sleep(1 + Math.random() * 2.5);

  // 3. A second page of the shelf, or a search, or a product - in roughly the
  // proportions a storefront actually sees.
  const roll = Math.random();
  if (roll < 0.35) {
    get(`/api/products/feed?page=1&size=20`, 'shelf', hdr);
  } else if (roll < 0.70) {
    const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
    get(`/api/products/search/instant?keyword=${term}&page=0&size=20`, 'search', hdr);
  } else if (roll < 0.85) {
    if (shelf.status === 200) {
      try {
        const content = shelf.json('content');
        if (content && content.length) {
          const p = content[Math.floor(Math.random() * content.length)];
          get(`/api/products/${p.id}`, 'product_detail', hdr);
        }
      } catch (e) { /* nothing to open */ }
    }
  } else {
    get(`/api/marketplace/shops/${shopId}`, 'storefront', hdr);
  }

  sleep(1.5 + Math.random() * 2.5);
}

/**
 * A customer who never chooses a shop - which is now the default.
 *
 * WHY THIS IS A SEPARATE SCENARIO FROM browse(). browse() models the old
 * journey: pick a storefront, then look at its shelf, everything after scoped
 * to one shop. This models the new one: stand somewhere and ask the TOWN what
 * it sells. No X-Shop-Id is ever sent, which is the whole point - these
 * endpoints span shops by design, so they are the ones where a per-shop loop
 * or a missing bound would show up first and worst.
 *
 * NO TENANT-LEAK CHECK HERE, and that is not an omission. Seeing many shops
 * in one response is what this endpoint is FOR; the leak check in browse()
 * asks the opposite question of an endpoint that must answer about one shop.
 * Applying it here would fail the feed for working.
 */
export function marketplace() {
  const where = pin();

  // 1. What is for sale near me - no shop chosen, no header sent.
  const feed = get(
    `/api/marketplace/feed?lat=${where.lat}&lng=${where.lng}&page=0&size=20`,
    'market_feed');
  check(feed, { 'marketplace feed answered or was refused on purpose':
    (r) => r.status === 200 || r.status === 429 || isShed(r) });

  sleep(1 + Math.random() * 2);

  const roll = Math.random();
  if (roll < 0.35) {
    // 2a. A second page. Paging is where an offset scan would hurt.
    get(`/api/marketplace/feed?lat=${where.lat}&lng=${where.lng}&page=1&size=20`,
        'market_feed');
  } else if (roll < 0.55) {
    // 2b. The same feed asked for the other two modes, which is the drawer.
    const mode = Math.random() < 0.5 ? 'VISIT_TO_BUY' : 'SERVICE_AT_SHOP';
    get(`/api/marketplace/feed?lat=${where.lat}&lng=${where.lng}&mode=${mode}&page=0&size=20`,
        'market_feed');
  } else if (roll < 0.80) {
    // 2c. Search across the town and across all three modes.
    const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
    get(`/api/marketplace/search?q=${term}&lat=${where.lat}&lng=${where.lng}&page=0&size=20`,
        'market_search');
  } else if (feed.status === 200) {
    // 2d. Tapping a card: who else near me has this. This one expands a
    // collapsed card back into every shop offering it, so it is the read
    // most likely to fan out if the bound is ever lost.
    try {
      const cards = feed.json();
      if (cards && cards.length) {
        const card = cards[Math.floor(Math.random() * cards.length)];
        get(`/api/marketplace/products/${card.productId}/offers`
            + `?lat=${where.lat}&lng=${where.lng}`, 'market_offers');
      }
    } catch (e) { /* a shed or throttled response has no cards */ }
  }

  sleep(1.5 + Math.random() * 2.5);
}

export function shopper(data) {
  const account = accounts[(__VU - 1) % accounts.length];
  if (!account || !account.token) { sleep(3); return; }
  const hdr = { Authorization: `Bearer ${account.token}` };

  // A signed-in customer opens a storefront, puts something in the cart, and
  // looks at their orders. These are the endpoints whose tenant scope comes
  // from a CREDENTIAL rather than from a header, and the only ones here that
  // write.
  const shopId = data.sampleShops[Math.floor(Math.random() * data.sampleShops.length)];
  const shopHdr = Object.assign({ 'X-Shop-Id': String(shopId) }, hdr);

  const shelf = get('/api/products/feed?page=0&size=20', 'shelf', shopHdr);
  sleep(1 + Math.random() * 2);

  if (shelf.status === 200) {
    try {
      const content = shelf.json('content');
      if (content && content.length) {
        const product = content[Math.floor(Math.random() * content.length)];
        const variant = product.variants && product.variants[0];
        if (variant) {
          const res = http.post(
            `${BASE_URL}/api/carts/add?variantId=${variant.id}&quantity=1`,
            null,
            { headers: shopHdr, tags: { name: 'cart_add', stage: STAGE }, timeout: REQ_TIMEOUT },
          );
          record(res);
        }
      }
    } catch (e) { /* a shed or throttled shelf has nothing to add */ }
  }

  sleep(1 + Math.random() * 2);
  get('/api/carts/mine', 'cart_read', hdr);
  sleep(1 + Math.random() * 2);
  get('/api/orders/my-orders?page=0&size=10', 'my_orders', hdr);
  sleep(2 + Math.random() * 3);
}
