#!/usr/bin/env bash
#
# THE MARKETPLACE, OVER REAL HTTP, AGAINST A RUNNING SERVER.
#
# WHAT THIS IS FOR. The test suite proves the application's behaviour with
# MockMvc and a transaction that rolls back. That is the right tool for it and
# it is not the same claim as "a server on the end of a socket answered
# correctly". This walks the customer-facing marketplace over real HTTP, with
# a real registration, a real JWT and real JSON, and then tries to break the
# tenant boundary by hand.
#
# IT TAKES A URL, so the same script covers a laptop, a staging box and - read
# only, carefully, and only when you mean it - production:
#
#   scripts/verify/smoke_api.sh http://localhost:8081/v1
#   scripts/verify/smoke_api.sh https://api.gpstore.co.in/v1
#
# WHAT IT WILL NOT DO. It places no orders, takes no payment and writes
# nothing a shop would see. It registers ONE throwaway customer - the app has
# no other way to obtain a token - on a 9999xxxxxxx number, and that is the
# entire footprint. Against production that is still a real row, so think
# before pointing it there.
#
# EVERY CHECK IS A CLAIM WITH AN EXPECTED STATUS. A 200 where a 403 belongs is
# a failure, and so is a 403 where a 200 belongs - a script that only ever
# demanded "not 500" would pass against a server that refused everything.
set -uo pipefail

BASE="${1:-http://localhost:8081/v1}"
BASE="${BASE%/}"
LAT="${LAT:-27.16231}"
LNG="${LNG:-83.940468}"

# WRITES NOTHING AT ALL when set to 1. The authenticated half of this script
# needs a token, and the only way to get one is to register - which is one
# real row in customers. That is a defensible footprint against production
# (see the Authentication section) but it is not NO footprint, and a probe
# that must not touch the database at all is a reasonable thing to want.
# Read-only keeps the public surface and the 401 checks and drops the rest.
READ_ONLY="${SMOKE_READ_ONLY:-0}"

# When set, /api/version must report exactly this commit. Lets a smoke run
# assert it is testing the build somebody thinks is deployed, rather than
# whatever happens to be running.
EXPECT_SHA="${EXPECT_SHA:-}"

pass=0; fail=0
RED=$'\033[31m'; GREEN=$'\033[32m'; OFF=$'\033[0m'

BODY="$(mktemp -t smoke_body.XXXXXX)"
trap 'rm -f "$BODY"' EXIT

# ONE REQUEST, AND THE TRUTH ABOUT IT.
#
# Two things here were quietly lying. `|| echo 000` appended to curl's own
# "000" and reported `got 000000`, a status that does not exist. And when a
# request never completed curl wrote no body, so the failure printed whatever
# the PREVIOUS check had left in the file - evidence from another request
# entirely. A smoke test whose whole product is trustworthy evidence cannot
# do either, so: truncate the body first, and take curl's exit separately.
request() {
  : > "$BODY"
  curl -sS -o "$BODY" -w '%{http_code}' --max-time 25 "$@" 2>/dev/null || true
}

# check <name> <expected-status> <curl args...>
check() {
  local name="$1" want="$2"; shift 2
  local got
  got=$(request "$@")
  got="${got:-000}"
  if [ "$got" = "$want" ]; then
    printf '  %sPASS%s  %-58s %s\n' "$GREEN" "$OFF" "$name" "$got"
    pass=$((pass+1))
  else
    printf '  %sFAIL%s  %-58s got %s, wanted %s\n' "$RED" "$OFF" "$name" "$got" "$want"
    head -c 200 "$BODY"; echo
    fail=$((fail+1))
  fi
}

# checkAny <name> <expected-a> <expected-b> <curl args...>
# For the few answers where two statuses are both correct - a route that 404s
# when a fixture is absent and 200s when it is present, say.
checkAny() {
  local name="$1" a="$2" b="$3"; shift 3
  local got
  got=$(request "$@")
  got="${got:-000}"
  if [ "$got" = "$a" ] || [ "$got" = "$b" ]; then
    printf '  %sPASS%s  %-58s %s\n' "$GREEN" "$OFF" "$name" "$got"
    pass=$((pass+1))
  else
    printf '  %sFAIL%s  %-58s got %s, wanted %s or %s\n' "$RED" "$OFF" "$name" "$got" "$a" "$b"
    head -c 200 "$BODY"; echo
    fail=$((fail+1))
  fi
}

say() { printf '\n== %s ==\n' "$1"; }

echo "Smoke target: $BASE"

say "Liveness"
check "GET /api/health"                     200 "$BASE/api/health"
check "GET /api/health/ready"               200 "$BASE/api/health/ready"

say "Deployed build"
check "GET /api/version"                    200 "$BASE/api/version"
DEPLOYED_SHA=$(curl -sS --max-time 25 "$BASE/api/version" 2>/dev/null \
        | python3 -c 'import json,sys; print(json.load(sys.stdin).get("gitCommit",""))' 2>/dev/null || echo "")
echo "  deployed gitCommit: ${DEPLOYED_SHA:-<none>}"
if [ -n "$EXPECT_SHA" ]; then
  if [ "$DEPLOYED_SHA" = "$EXPECT_SHA" ]; then
    printf '  %sPASS%s  %-58s %s\n' "$GREEN" "$OFF" "/api/version matches expected commit" "${DEPLOYED_SHA:0:12}"
    pass=$((pass+1))
  else
    printf '  %sFAIL%s  %-58s got %s, wanted %s\n' "$RED" "$OFF" \
      "/api/version matches expected commit" "${DEPLOYED_SHA:-<none>}" "$EXPECT_SHA"
    fail=$((fail+1))
  fi
fi

say "Marketplace, unauthenticated"
# These are deliberately public: a customer who has just installed the app has
# no shop yet, and asking them to have one before they can find out which
# shops exist is a 403 on the first screen.
check "GET /api/marketplace/mode"           200 "$BASE/api/marketplace/mode"
check "GET /api/marketplace/discovery"      200 "$BASE/api/marketplace/discovery?lat=$LAT&lng=$LNG"
check "GET /api/marketplace/shops"          200 "$BASE/api/marketplace/shops?lat=$LAT&lng=$LNG"

# How many shops actually serve that pin decides whether the rest means
# anything. An empty list is a correct answer and a useless fixture.
SHOPS=$(curl -sS --max-time 25 "$BASE/api/marketplace/discovery?lat=$LAT&lng=$LNG" 2>/dev/null \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d.get("shops") or []))' 2>/dev/null || echo 0)
echo "  shops serving ($LAT, $LNG): $SHOPS"
if [ "$SHOPS" -lt 2 ]; then
  echo "  NOTE: fewer than two shops reach this pin, so the two-shop checks below"
  echo "        cannot mean much. Seed with scripts/verify/seed_two_shop_testbed.sql"
  echo "        or pass LAT/LNG for a point two shops deliver to."
fi

SHOP_A=$(curl -sS --max-time 25 "$BASE/api/marketplace/discovery?lat=$LAT&lng=$LNG" 2>/dev/null \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); s=d.get("shops") or []; print(s[0]["shopId"] if s else "")' 2>/dev/null)
SHOP_B=$(curl -sS --max-time 25 "$BASE/api/marketplace/discovery?lat=$LAT&lng=$LNG" 2>/dev/null \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); s=d.get("shops") or []; print(s[1]["shopId"] if len(s)>1 else "")' 2>/dev/null)
echo "  shop A=$SHOP_A  shop B=$SHOP_B"

if [ -n "$SHOP_A" ]; then
  check "GET /api/marketplace/shops/{A}"    200 "$BASE/api/marketplace/shops/$SHOP_A"
fi
# A shop the marketplace does not show is indistinguishable from one that
# never existed. Not 403 - 404, deliberately: whether a particular shop is
# suspended is between the platform and that merchant.
check "GET /api/marketplace/shops/99999999" 404 "$BASE/api/marketplace/shops/99999999"

say "No token at all"
check "GET /api/carts/mine unauthenticated"          401 "$BASE/api/carts/mine"
check "GET /api/orders/my-orders unauthenticated"    401 "$BASE/api/orders/my-orders"

if [ "$READ_ONLY" = "1" ]; then
  say "Read-only mode"
  echo "  SMOKE_READ_ONLY=1, so nothing below runs and nothing was written."
  echo "  Skipped: registration, the signed-in surfaces, the customer-is-not-a-"
  echo "  merchant authorization matrix, the IDOR probes, the X-Shop-Id probes"
  echo "  and the payment-assertion probe. Those need a token."
  printf '\n== %d passed, %d failed ==\n' "$pass" "$fail"
  [ "$fail" -eq 0 ] || exit 1
  exit 0
fi

say "Authentication"
# WHAT THIS WRITES, EXACTLY: one row in customers - name "SMOKE TEST", a
# 9999xxxxxxx phone, an @example.invalid email, role CUSTOMER, verified=false.
# No order, no payment, no cart content, no shop, no worker. The app offers no
# other way to obtain a customer token, so this is the minimum a signed-in
# check can cost. Set SMOKE_READ_ONLY=1 to pay nothing and test less.
STAMP=$(date +%s)
PHONE="9999$(printf '%06d' $((STAMP % 1000000)))"
EMAIL="smoke-$STAMP@example.invalid"
REG=$(curl -sS --max-time 25 -H 'Content-Type: application/json' \
        -d "{\"name\":\"SMOKE TEST\",\"email\":\"$EMAIL\",\"phone\":\"$PHONE\",\"password\":\"smoke-pass-1234\"}" \
        "$BASE/api/auth/register" 2>/dev/null)
TOKEN=$(printf '%s' "$REG" | python3 -c 'import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    print(""); raise SystemExit
print(d.get("accessToken") or d.get("token") or "")' 2>/dev/null)

if [ -n "$TOKEN" ]; then
  printf '  %sPASS%s  %-58s token issued\n' "$GREEN" "$OFF" "POST /api/auth/register"
  pass=$((pass+1))
else
  printf '  %sFAIL%s  %-58s no token\n' "$RED" "$OFF" "POST /api/auth/register"
  printf '        %s\n' "$(printf '%s' "$REG" | head -c 200)"
  fail=$((fail+1))
fi

AUTH=(-H "Authorization: Bearer $TOKEN")

say "A signed-in customer's own surfaces"
check "GET /api/carts/mine"                 200 "${AUTH[@]}" "$BASE/api/carts/mine"
check "GET /api/carts/mine/by-shop"         200 "${AUTH[@]}" "$BASE/api/carts/mine/by-shop"
check "GET /api/orders/my-orders"           200 "${AUTH[@]}" "$BASE/api/orders/my-orders?page=0&size=5"
check "GET /api/addresses/mine"             200 "${AUTH[@]}" "$BASE/api/addresses/mine"
check "GET /api/preferred-shops"            200 "${AUTH[@]}" "$BASE/api/preferred-shops"
check "GET /api/categories"                 200 "${AUTH[@]}" "$BASE/api/categories"
check "GET /api/products/feed"              200 "${AUTH[@]}" "$BASE/api/products/feed?page=0&size=5"
check "GET /api/products/search/instant"    200 "${AUTH[@]}" "$BASE/api/products/search/instant?keyword=dal&page=0&size=5"

say "Authorization: a customer is not a merchant, an admin, or a worker"
# THE POINT OF THE WHOLE EXERCISE. Every one of these must be refused. A 200
# here is a release-stopping defect, not a curiosity.
check "GET /api/shop/profile (merchant only)"        403 "${AUTH[@]}" "$BASE/api/shop/profile"
check "GET /api/shop/listings (merchant only)"       403 "${AUTH[@]}" "$BASE/api/shop/listings"
check "GET /api/shop/earnings (merchant money)"      403 "${AUTH[@]}" "$BASE/api/shop/earnings"
check "GET /api/shop/staff (merchant's workers)"     403 "${AUTH[@]}" "$BASE/api/shop/staff"
check "GET /api/shop/governance (private record)"    403 "${AUTH[@]}" "$BASE/api/shop/governance"
check "GET /api/platform/governance/actions"         403 "${AUTH[@]}" "$BASE/api/platform/governance/actions"
check "GET /api/platform/overview (platform admin)"   403 "${AUTH[@]}" "$BASE/api/platform/overview"
check "GET /api/platform/merchants (platform admin)"  403 "${AUTH[@]}" "$BASE/api/platform/merchants"
check "GET /api/platform/shops (every shop)"          403 "${AUTH[@]}" "$BASE/api/platform/shops"
# ONE MERCHANT AND EVERY SHOP UNDER IT, addressed by an id the caller picks.
# The route takes any merchant id, so an unguarded one is not a leak of a
# single business - it is a way to walk every business on the platform by
# counting upwards. Exact 403: a 404 would mean it is not deployed at all.
check "GET /api/platform/merchants/1/detail (a business and its shops)" 403 \
  "${AUTH[@]}" "$BASE/api/platform/merchants/1/detail"
check "GET /api/admin/workers (platform admin)"       403 "${AUTH[@]}" "$BASE/api/admin/workers"
check "GET /api/cancellation-dues/outstanding"       403 "${AUTH[@]}" "$BASE/api/cancellation-dues/outstanding"
check "GET /api/shop-ratings/manage"                 403 "${AUTH[@]}" "$BASE/api/shop-ratings/manage"

# A CUSTOMER MUST NOT BE ABLE TO OPEN A STAFF LOGIN. These two routes are
# the only ones in the system that mint an ADMIN account and hand back a
# usable password, so a missing SecurityConfig rule here would let anybody
# who can register make themselves a shop administrator. Probed with a real
# body on purpose: a refusal that only holds for a malformed request is not
# a refusal. Nothing is created either way - a 403 happens before the
# controller - and a 404 would mean the route is not deployed at all, which
# is why it is not accepted.
check "POST /api/platform/staff (mints an ADMIN login)" 403 \
  -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"fullName":"Smoke Probe","email":"smoke-probe@example.invalid","role":"ADMIN"}' \
  "$BASE/api/platform/staff"
check "POST /api/platform/staff/1/reset-password" 403 \
  -X POST "${AUTH[@]}" "$BASE/api/platform/staff/1/reset-password"
# THE ONE-SCREEN ROUTE IS THE SAME DANGER IN ONE CALL. /api/platform/onboard
# opens an ADMIN login, approves a business and opens a shop in a single
# request, so a missing rule here is every hole the two above would be, plus
# a merchant. Exact 403, for the same reason: 404 would mean not deployed.
# A CUSTOMER MUST NOT BE ABLE TO REPLACE A MERCHANT'S SECOND FACTOR. Reissuing
# an activation code kills the old one and mints a new one, so an unguarded
# route here is a way to lock a merchant out of their own claim and take it.
check "POST /api/platform/staff/1/reissue-activation-code" 403 \
  -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"reason":"smoke probe"}' \
  "$BASE/api/platform/staff/1/reissue-activation-code"
check "POST /api/platform/onboard (mints an ADMIN login and an approved shop)" 403 \
  -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"businessName":"Smoke Probe Stores","ownerName":"Smoke Probe","ownerEmail":"smoke-probe-onboard@example.invalid","latitude":26.7606,"longitude":83.3732,"maxDeliveryRadiusKm":5}' \
  "$BASE/api/platform/onboard"

say "IDOR: naming somebody else's row does not fetch it"
# Not 200. 403 or 404 are both correct - refusing to say whether the row
# exists is itself a defensible answer.
checkAny "GET /api/orders/1 (not this customer's)"   403 404 "${AUTH[@]}" "$BASE/api/orders/1"
checkAny "GET /api/orders/999999"                    403 404 "${AUTH[@]}" "$BASE/api/orders/999999"
checkAny "GET /api/payments/order/1"                 403 404 "${AUTH[@]}" "$BASE/api/payments/order/1"
checkAny "GET /api/invoices/my-order/1"              403 404 "${AUTH[@]}" "$BASE/api/invoices/my-order/1"

say "X-Shop-Id may narrow a scope, never grant one"
# A header cannot hand a customer a shop's private surface. If any of these
# turns into a 200, the tenant boundary is decorative.
if [ -n "$SHOP_B" ]; then
  check "GET /api/shop/listings with X-Shop-Id: B"   403 "${AUTH[@]}" -H "X-Shop-Id: $SHOP_B" "$BASE/api/shop/listings"
  check "GET /api/shop/governance with X-Shop-Id: B" 403 "${AUTH[@]}" -H "X-Shop-Id: $SHOP_B" "$BASE/api/shop/governance"
fi
check "GET /api/shop/earnings with X-Shop-Id: 99999" 403 "${AUTH[@]}" -H "X-Shop-Id: 99999" "$BASE/api/shop/earnings"

say "Payment is not something a client may assert"
# /verify takes NO request body: it asks the provider and applies the answer.
# A body claiming success must change nothing - the worst outcome here would
# be a 200 that marked an order paid.
checkAny "POST /api/payments/order/1/verify (claimed success)" 403 404 \
  -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"payment_success":true,"status":"PAID","amount":0}' \
  "$BASE/api/payments/order/1/verify"

printf '\n== %d passed, %d failed ==\n' "$pass" "$fail"
[ "$fail" -eq 0 ] || exit 1
