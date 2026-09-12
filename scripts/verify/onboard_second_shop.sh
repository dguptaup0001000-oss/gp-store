#!/usr/bin/env bash
#
# ONBOARD A SECOND REAL SHOP, THROUGH THE PRODUCTION APIs, OVER REAL HTTP -
# AND THEN TRY TO BREAK THE WALL BETWEEN IT AND SHOP #1.
#
# WHAT THIS IS FOR. The suite already onboards a second merchant
# (SecondMerchantOnboardingTest) and already attacks the tenant boundary
# (CrossTenantApiAccessTest). Both run under MockMvc, inside a transaction,
# in one JVM. That is the right tool for proving behaviour and it is NOT the
# same claim as "a merchant was onboarded on a running server and could not
# see the other shop's business". This script makes the second claim.
#
# IT USES NO SHORTCUT. Every step below is a route a real merchant's
# onboarding goes through. There is no SQL here, no seeding, no test-only
# endpoint and no flag that makes the server behave differently because this
# script is the caller. If a step cannot be done through the API, this script
# cannot do it either - which is the point.
#
# WHAT IT CREATES, and you should read this before pointing it anywhere real:
#   - one merchant, one shop, both flagged demo
#   - one shop_staff row joining the owner you name to that shop
#   - one listing and one inventory row on the NEW shop only
# It places no orders, takes no payment, and writes NOTHING to Shop #1 -
# every request it makes against Shop #1 is one it expects to be REFUSED.
#
# CREDENTIALS ARE YOURS TO SUPPLY. This script will not register an admin, will
# not promote an account, and will not invent a token. Self-service escalation
# to a shop-owning role is a hole, not a convenience, and the server is right
# to have no route for it.
#
#   PLATFORM_EMAIL    a SUPER_ADMIN account. Opens merchants and shops.
#   PLATFORM_PASSWORD its password.
#   OWNER_B_EMAIL     an EXISTING admin-role account to own the new shop.
#   OWNER_B_PASSWORD  its password.
#   OWNER_A_EMAIL     Shop #1's own admin account. Only ever used to prove that
#   OWNER_A_PASSWORD  Shop #1 CANNOT reach the new shop's business.
#
#   PLATFORM_EMAIL=... PLATFORM_PASSWORD=... OWNER_B_EMAIL=... OWNER_B_PASSWORD=... \
#   OWNER_A_EMAIL=... OWNER_A_PASSWORD=... \
#     scripts/verify/onboard_second_shop.sh http://localhost:8081/v1
#
# CREDENTIALS, NOT TOKENS, AND THAT IS DELIBERATE. This script used to take
# ready-made bearer tokens and it had a fuse in it: access tokens last fifteen
# minutes, a full run takes longer than you would think, and section 7 came
# back 401 against a token minted before section 1. A 401 is indistinguishable
# from a tenancy refusal if you are not reading carefully, which is the worst
# possible way for a verification script to fail. It signs in when it needs to
# instead.
#
# EVERY CHECK NAMES THE STATUS IT EXPECTS. A 200 where a 404 belongs is a
# failure and so is the reverse: a script that only demanded "not 500" would
# pass against a server that refused every request, which is the exact
# failure mode a tenancy bug looks like from the outside.
#
# REFUSALS ARE 404, NOT 403. A 403 on an id you guessed confirms the id
# exists. Walking a range of ids and reading which come back 403 maps out how
# much business the other shop is doing. "Not found" is the only answer that
# leaks nothing, so 403 is treated as a FAILURE below, not as a pass.
set -uo pipefail

BASE="${1:-http://localhost:8081/v1}"
BASE="${BASE%/}"

# Where the new shop sits. Defaults to the same pin the smoke script uses so
# the two agree about which market this is.
LAT="${LAT:-27.16231}"
LNG="${LNG:-83.940468}"

pass=0; fail=0
RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; OFF=$'\033[0m'
BODY=$(mktemp)
trap 'rm -f "$BODY"' EXIT

need() {
  local var="$1"
  if [ -z "${!var:-}" ]; then
    printf '%sMissing %s.%s Read the header of this script - it says what each\n' \
      "$RED" "$var" "$OFF"
    printf 'credential is for and why the script will not manufacture one.\n'
    exit 2
  fi
}
need PLATFORM_EMAIL
need PLATFORM_PASSWORD
need OWNER_B_EMAIL
need OWNER_B_PASSWORD
need OWNER_A_EMAIL
need OWNER_A_PASSWORD

say() { printf '\n== %s ==\n' "$1"; }

# call <method> <path> <token> <json-or-empty> -> status, body in $BODY
call() {
  local method="$1" path="$2" token="$3" json="${4:-}"
  local args=(-sS -o "$BODY" -w '%{http_code}' --max-time 30 -X "$method"
              -H "Authorization: Bearer $token")
  if [ -n "$json" ]; then
    args+=(-H 'Content-Type: application/json' -d "$json")
  fi
  curl "${args[@]}" "$BASE$path" 2>/dev/null || echo 000
}

# check <name> <expected> <method> <path> <token> [json]
check() {
  local name="$1" want="$2"; shift 2
  local got; got=$(call "$@")
  if [ "$got" = "$want" ]; then
    printf '  %sPASS%s  %-62s %s\n' "$GREEN" "$OFF" "$name" "$got"
    pass=$((pass+1)); return 0
  fi
  printf '  %sFAIL%s  %-62s got %s, wanted %s\n' "$RED" "$OFF" "$name" "$got" "$want"
  head -c 300 "$BODY"; echo
  fail=$((fail+1)); return 1
}

# The reverse direction is the one people forget. Same expectation, worded so
# a failure reads as what it is.
refused() {
  local name="$1"; shift
  local got; got=$(call "$@")
  if [ "$got" = "404" ]; then
    printf '  %sPASS%s  %-62s 404\n' "$GREEN" "$OFF" "$name"
    pass=$((pass+1)); return 0
  fi
  if [ "$got" = "403" ]; then
    printf '  %sFAIL%s  %-62s 403 - a refusal that confirms the id exists\n' \
      "$RED" "$OFF" "$name"
  else
    printf '  %sFAIL%s  %-62s got %s, wanted 404\n' "$RED" "$OFF" "$name" "$got"
    head -c 300 "$BODY"; echo
  fi
  fail=$((fail+1)); return 1
}

jsonfield() { python3 -c '
import json,sys
try: d = json.load(open(sys.argv[1]))
except Exception: print(""); raise SystemExit
cur = d
for k in sys.argv[2].split("."):
    if isinstance(cur, list): cur = cur[int(k)] if cur else None
    elif isinstance(cur, dict): cur = cur.get(k)
    else: cur = None
    if cur is None: break
print("" if cur is None else cur)' "$BODY" "$1" 2>/dev/null; }

# Signs in and prints a fresh token. Called at the point of use rather than
# once at the top, so a long run never attacks with an expired credential.
login() {
  curl -sS -o "$BODY" --max-time 30 -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$2\"}" \
    "$BASE/api/auth/login" >/dev/null 2>&1
  local t; t=$(jsonfield token)
  [ -n "$t" ] || t=$(jsonfield accessToken)
  printf '%s' "$t"
}

echo "Target:  $BASE"
echo "New shop pin: $LAT,$LNG"
STAMP=$(date +%s)

PLATFORM_TOKEN=$(login "$PLATFORM_EMAIL" "$PLATFORM_PASSWORD")
if [ -z "$PLATFORM_TOKEN" ]; then
  printf '%sThe platform administrator could not sign in.%s\n' "$RED" "$OFF"; exit 1
fi

# =====================================================================
say "1. The platform registers the business"
# =====================================================================
# A NEW MERCHANT IS AN APPLICATION, NOT A SHOP. Registered, reviewed,
# approved - three recorded events, because "somebody checked this business's
# papers" is a real thing that happened and jumping straight to APPROVED
# would leave no trace that it did.
check "POST /api/platform/merchants" 200 \
  POST "/api/platform/merchants" "$PLATFORM_TOKEN" \
  "{\"legalName\":\"Verification Kirana $STAMP\",\"displayName\":\"Verification Kirana\",\"contactPhone\":\"9800$(printf '%06d' $((STAMP % 1000000)))\",\"demo\":true}" \
  || { echo "cannot continue without a merchant"; exit 1; }
MERCHANT=$(jsonfield id)
echo "     merchant id: $MERCHANT"

check "PUT  merchants/$MERCHANT/status -> PENDING_REVIEW" 200 \
  PUT "/api/platform/merchants/$MERCHANT/status" "$PLATFORM_TOKEN" \
  '{"status":"PENDING_REVIEW","reason":"papers submitted"}'
check "PUT  merchants/$MERCHANT/status -> APPROVED" 200 \
  PUT "/api/platform/merchants/$MERCHANT/status" "$PLATFORM_TOKEN" \
  '{"status":"APPROVED","reason":"papers checked"}'

# =====================================================================
say "2. The platform opens the shop"
# =====================================================================
check "POST /api/platform/shops" 200 \
  POST "/api/platform/shops" "$PLATFORM_TOKEN" \
  "{\"merchantId\":$MERCHANT,\"code\":\"VER-$STAMP\",\"displayName\":\"Verification Kirana\",\"latitude\":$LAT,\"longitude\":$LNG,\"maxDeliveryRadiusKm\":5,\"timeZone\":\"Asia/Kolkata\"}" \
  || { echo "cannot continue without a shop"; exit 1; }
SHOP_B=$(jsonfield id)
echo "     shop id: $SHOP_B"

# A shop cannot be opened under a business nobody checked. Proving the refusal
# matters as much as proving the happy path: without it, "approved" is
# decoration.
check "POST /api/platform/shops under an unknown merchant is refused" 404 \
  POST "/api/platform/shops" "$PLATFORM_TOKEN" \
  "{\"merchantId\":999999999,\"code\":\"VER-X-$STAMP\",\"displayName\":\"Nobody\",\"latitude\":$LAT,\"longitude\":$LNG,\"maxDeliveryRadiusKm\":5,\"timeZone\":\"Asia/Kolkata\"}"

# =====================================================================
say "3. The owner is put on the shop's staff"
# =====================================================================
check "POST /api/auth/login as the new owner" 200 \
  POST "/api/auth/login" "none" \
  "{\"email\":\"$OWNER_B_EMAIL\",\"password\":\"$OWNER_B_PASSWORD\"}" \
  || { echo "the owner account could not sign in - check OWNER_B_EMAIL/PASSWORD"; exit 1; }
OWNER_B_ID=$(jsonfield customerId)
[ -n "$OWNER_B_ID" ] || OWNER_B_ID=$(jsonfield user.id)
TOKEN_B=$(jsonfield token)
[ -n "$TOKEN_B" ] || TOKEN_B=$(jsonfield accessToken)
if [ -z "$TOKEN_B" ] || [ -z "$OWNER_B_ID" ]; then
  printf '  %sFAIL%s  login gave no usable token/id\n' "$RED" "$OFF"; exit 1
fi
echo "     owner account: $OWNER_B_ID"

check "POST /api/platform/shops/$SHOP_B/staff" 200 \
  POST "/api/platform/shops/$SHOP_B/staff" "$PLATFORM_TOKEN" \
  "{\"customerId\":$OWNER_B_ID,\"asDefault\":true}"

# The token was issued before the staff row existed. Re-issue it so the shop
# scope is the one the credential actually resolves to now - the same thing a
# merchant does by signing in again after being given access.
check "POST /api/auth/login again, now that they have a shop" 200 \
  POST "/api/auth/login" "none" \
  "{\"email\":\"$OWNER_B_EMAIL\",\"password\":\"$OWNER_B_PASSWORD\"}"
TOKEN_B=$(jsonfield token)
[ -n "$TOKEN_B" ] || TOKEN_B=$(jsonfield accessToken)

# =====================================================================
say "4. The new shop is the one the owner sees - and the only one"
# =====================================================================
check "GET  /api/shop/profile answers with the NEW shop" 200 \
  GET "/api/shop/profile" "$TOKEN_B"
SEEN=$(jsonfield id)
if [ "$SEEN" = "$SHOP_B" ]; then
  printf '  %sPASS%s  %-62s shop %s\n' "$GREEN" "$OFF" "the profile is shop $SHOP_B" "$SEEN"
  pass=$((pass+1))
else
  printf '  %sFAIL%s  %-62s got shop %s\n' "$RED" "$OFF" \
    "the owner resolved to the WRONG shop" "$SEEN"
  printf '        This is the single most important line in the script. If the\n'
  printf '        new owner resolves to Shop #1, every isolation check below is\n'
  printf '        Shop #1 talking to itself and proves nothing.\n'
  fail=$((fail+1))
fi

check "GET  /api/shop/readiness tells the owner what is left" 200 \
  GET "/api/shop/readiness" "$TOKEN_B"
if grep -q '"name":"shop-hours","done":false' "$BODY"; then
  printf '  %sPASS%s  %-62s named\n' "$GREEN" "$OFF" "the checklist says the hours are not this shop's yet"
  pass=$((pass+1))
else
  printf '  %sFAIL%s  %-62s\n' "$RED" "$OFF" "the hours step is missing from the checklist"
  fail=$((fail+1))
fi

# =====================================================================
say "5. The owner stocks their own shelf - no SQL, no shortcut"
# =====================================================================
# THE SHARED CATALOGUE, NOT THIS SHOP'S SHELF. /api/products answers with
# what THIS shop sells, which for a shop opened ninety seconds ago is
# correctly nothing - the first run of this script asked there and skipped the
# whole section. A merchant picking their opening range browses the catalogue
# every shop draws from, which is /api/products/admin/all.
check "GET  /api/products/admin/all (the shared catalogue)" 200 \
  GET "/api/products/admin/all?page=0&size=200" "$TOKEN_B"
VARIANT=$(python3 -c '
import json,sys
try: d = json.load(open(sys.argv[1]))
except Exception: d = []
for p in (d if isinstance(d, list) else d.get("content", [])):
    for v in (p.get("variants") or []):
        if v.get("id"): print(v["id"]); raise SystemExit
print("")' "$BODY" 2>/dev/null)

# A SHOP'S OWN SHELF IS EMPTY AND MUST READ AS EMPTY. This is the same
# isolation claim as the sections below, made where it is easiest to get
# wrong: the catalogue is shared, the shelf is not.
check "GET  /api/products (this shop's shelf)" 200 GET "/api/products?page=0&size=5" "$TOKEN_B"
if [ "$(jsonfield 0.id)" = "" ]; then
  printf '  %sPASS%s  %-62s empty\n' "$GREEN" "$OFF" "a shop that has listed nothing sells nothing"
  pass=$((pass+1))
else
  printf '  %sFAIL%s  %-62s %s\n' "$RED" "$OFF" \
    "a shop that has listed nothing is showing stock" "$(jsonfield 0.id)"
  fail=$((fail+1))
fi

if [ -z "$VARIANT" ]; then
  printf '  %sFAIL%s  %-62s\n' "$RED" "$OFF" \
    "no catalogue variant to list - onboarding cannot be verified"
  fail=$((fail+1))
else
  echo "     catalogue variant: $VARIANT"
  check "PUT  /api/shop/listings/$VARIANT (their price)" 200 \
    PUT "/api/shop/listings/$VARIANT" "$TOKEN_B" \
    '{"sellingPrice":88.00,"mrp":110.00,"available":true,"active":true}'

  # THE STEP THAT HAD NO ROUTE UNTIL NOW. Before this endpoint existed the
  # only way to open a shelf was to write to the inventory table directly,
  # which is what the onboarding test did and what a real merchant could not.
  check "PUT  /api/shop/listings/$VARIANT/stock (their stock)" 200 \
    PUT "/api/shop/listings/$VARIANT/stock" "$TOKEN_B" '{"stock":25,"minimumStock":5}'
  check "GET  /api/shop/listings/$VARIANT/stock reads it back" 200 \
    GET "/api/shop/listings/$VARIANT/stock" "$TOKEN_B"
  if grep -q '"stock":25' "$BODY"; then
    printf '  %sPASS%s  %-62s 25\n' "$GREEN" "$OFF" "the shelf says what the owner said"
    pass=$((pass+1))
  else
    printf '  %sFAIL%s  %-62s\n' "$RED" "$OFF" "the stock read back does not match"
    head -c 200 "$BODY"; echo
    fail=$((fail+1))
  fi

  # Stock for something this shop does not sell is not a thing.
  check "PUT  stock for an unlisted item is refused" 404 \
    PUT "/api/shop/listings/999999999/stock" "$TOKEN_B" '{"stock":5}'
fi

# =====================================================================
say "6. Shop #2 cannot reach Shop #1's business"
# =====================================================================
# EVERY ID BELOW IS ONE SHOP #1 REALLY HAS. That is the whole attack: a
# shopkeeper with a legitimate login subtracting one from their own id. The
# ids are discovered from Shop #1's own token, which is why SHOP_A_TOKEN is
# required - guessing ids would make a green run meaningless.
SHOP_A_TOKEN=$(login "$OWNER_A_EMAIL" "$OWNER_A_PASSWORD")
if [ -z "$SHOP_A_TOKEN" ]; then
  printf '  %sFAIL%s  shop #1 could not sign in - the ids below cannot be discovered\n' \
    "$RED" "$OFF"
  fail=$((fail+1))
fi
A_ORDER=""; A_COUPON=""; A_RIDER=""
if [ "$(call GET "/api/orders/admin/all?page=0&size=1" "$SHOP_A_TOKEN")" = "200" ]; then
  A_ORDER=$(jsonfield content.0.id)
  [ -n "$A_ORDER" ] || A_ORDER=$(jsonfield 0.id)
fi
if [ "$(call GET "/api/coupons" "$SHOP_A_TOKEN")" = "200" ]; then
  A_COUPON=$(jsonfield 0.id)
fi
# There is no GET /api/coupons/{id} - the first run of this script guessed one
# and got a 405. The write is the attack that matters anyway: reading a
# competitor's discount is bad, rewriting it is worse.
if [ "$(call GET "/api/delivery-partners" "$SHOP_A_TOKEN")" = "200" ]; then
  A_RIDER=$(jsonfield 0.id)
fi
echo "     shop #1 ids in play: order=${A_ORDER:-none} coupon=${A_COUPON:-none} rider=${A_RIDER:-none}"

if [ -n "$A_ORDER" ]; then
  refused "GET  /api/orders/$A_ORDER as shop #2" GET "/api/orders/$A_ORDER" "$TOKEN_B"
  refused "PUT  /api/orders/$A_ORDER/status as shop #2" \
    PUT "/api/orders/$A_ORDER/status?status=CONFIRMED" "$TOKEN_B"
else
  printf '  %sSKIP%s  shop #1 has no order to attack from here\n' "$YELLOW" "$OFF"
fi
if [ -n "$A_COUPON" ]; then
  refused "PUT  /api/coupons/$A_COUPON as shop #2" \
    PUT "/api/coupons/$A_COUPON" "$TOKEN_B" \
    '{"code":"HIJACKED","discountType":"PERCENTAGE","discountValue":90,"active":true}'
else
  printf '  %sSKIP%s  shop #1 has no offer to attack from here\n' "$YELLOW" "$OFF"
fi
if [ -n "$A_RIDER" ]; then
  refused "GET  /api/delivery-partners/$A_RIDER as shop #2" \
    GET "/api/delivery-partners/$A_RIDER" "$TOKEN_B"
else
  printf '  %sSKIP%s  shop #1 has no rider to attack from here\n' "$YELLOW" "$OFF"
fi

# The lists are the quieter leak: no id guessing, just "show me everything".
check "GET  /api/orders/admin/all as shop #2" 200 GET "/api/orders/admin/all?page=0&size=50" "$TOKEN_B"
if grep -q '"totalElements":0' "$BODY" || ! grep -q '"id"' "$BODY"; then
  printf '  %sPASS%s  %-62s empty\n' "$GREEN" "$OFF" "a brand new shop's order list is empty"
  pass=$((pass+1))
else
  printf '  %sFAIL%s  %-62s\n' "$RED" "$OFF" \
    "a shop that has never traded is showing orders"
  head -c 300 "$BODY"; echo
  fail=$((fail+1))
fi

check "GET  /api/delivery-partners as shop #2" 200 GET "/api/delivery-partners" "$TOKEN_B"
if [ "$(jsonfield 0.id)" = "" ]; then
  printf '  %sPASS%s  %-62s empty\n' "$GREEN" "$OFF" "a shop that has hired nobody has an empty roster"
  pass=$((pass+1))
else
  printf '  %sFAIL%s  %-62s rider %s\n' "$RED" "$OFF" \
    "a shop that has hired nobody is showing riders" "$(jsonfield 0.id)"
  fail=$((fail+1))
fi

check "GET  /api/shop/earnings as shop #2" 200 GET "/api/shop/earnings" "$TOKEN_B"

# The platform surface is not the merchant's. A shopkeeper holding a perfectly
# good admin token must not be able to read the market.
check "GET  /api/platform/overview as shop #2 is refused" 403 \
  GET "/api/platform/overview" "$TOKEN_B"
check "GET  /api/platform/shops as shop #2 is refused" 403 \
  GET "/api/platform/shops" "$TOKEN_B"

# =====================================================================
say "7. And Shop #1 cannot reach Shop #2's - the direction people forget"
# =====================================================================
SHOP_A_TOKEN=$(login "$OWNER_A_EMAIL" "$OWNER_A_PASSWORD")
if [ -n "${VARIANT:-}" ]; then
  # Shop #1 asking for the new shop's stock must be told it does not list it,
  # never how much of it there is next door.
  got=$(call GET "/api/shop/listings/$VARIANT/stock" "$SHOP_A_TOKEN")
  if [ "$got" = "404" ]; then
    printf '  %sPASS%s  %-62s 404\n' "$GREEN" "$OFF" "shop #1 cannot read shop #2's stock"
    pass=$((pass+1))
  elif [ "$got" = "200" ] && ! grep -q '"stock":25' "$BODY"; then
    # Shop #1 legitimately lists the same catalogue item. That is fine - what
    # matters is the number is ITS OWN, not the 25 shop #2 just set.
    printf '  %sPASS%s  %-62s own count\n' "$GREEN" "$OFF" \
      "shop #1 reads its own stock for the shared item"
    pass=$((pass+1))
  else
    printf '  %sFAIL%s  %-62s got %s\n' "$RED" "$OFF" \
      "shop #1 is reading shop #2's stock" "$got"
    head -c 200 "$BODY"; echo
    fail=$((fail+1))
  fi
fi

check "GET  /api/shop/profile as shop #1 is still shop #1" 200 GET "/api/shop/profile" "$SHOP_A_TOKEN"
SEEN_A=$(jsonfield id)
if [ -n "$SEEN_A" ] && [ "$SEEN_A" != "$SHOP_B" ]; then
  printf '  %sPASS%s  %-62s shop %s\n' "$GREEN" "$OFF" \
    "onboarding shop #2 did not move shop #1" "$SEEN_A"
  pass=$((pass+1))
else
  printf '  %sFAIL%s  %-62s got %s\n' "$RED" "$OFF" \
    "shop #1's own profile changed under it" "$SEEN_A"
  fail=$((fail+1))
fi

# =====================================================================
say "8. The new shop is real to a customer, not just to its owner"
# =====================================================================
# A SHOP OPENS AS A DRAFT AND THE STOREFRONT IS RIGHT TO HIDE IT. The first
# run of this script asked for the storefront here and got a 404, which was
# the server being correct and the script being wrong: nothing had thrown the
# switches yet. Proving the 404 first is worth a line - a draft shop that a
# customer could already be offered would be the real bug.
check "GET  /api/marketplace/shops/$SHOP_B while still a DRAFT is hidden" 404 \
  GET "/api/marketplace/shops/$SHOP_B" "none"

# TWO SWITCHES, NOT ONE, and both are real. APPROVED means the papers are in
# order; ACTIVE means the business is trading. A shop can be ACTIVE under a
# merchant that is only APPROVED - everything built, nothing selling - which
# is the state a real onboarding sits in until somebody throws the second.
check "PUT  merchants/$MERCHANT/status -> ACTIVE" 200 \
  PUT "/api/platform/merchants/$MERCHANT/status" "$PLATFORM_TOKEN" \
  '{"status":"ACTIVE","reason":"open for business"}'
check "PUT  shops/$SHOP_B/status -> ACTIVE" 200 \
  PUT "/api/platform/shops/$SHOP_B/status" "$PLATFORM_TOKEN" \
  '{"status":"ACTIVE","reason":"ready to trade"}'

# PUBLIC, UNAUTHENTICATED, exactly as a phone sees it. A shop that only its
# owner can see is not onboarded, it is filed.
check "GET  /api/marketplace/shops/$SHOP_B (public storefront)" 200 \
  GET "/api/marketplace/shops/$SHOP_B" "none"

# AND SHOP #1 IS STILL THERE. Onboarding a competitor must not disturb the
# shop that was already trading - the whole brief is "Shop #1 unchanged".
check "GET  /api/marketplace/shops/1 (shop #1 still public)" 200 \
  GET "/api/marketplace/shops/1" "none"

# =====================================================================
printf '\n== Result ==\n'
printf '  %s%d passed%s, %s%d failed%s\n' "$GREEN" "$pass" "$OFF" \
  "$([ "$fail" -gt 0 ] && echo "$RED" || echo "$GREEN")" "$fail" "$OFF"
cat <<EOF

  Created: merchant $MERCHANT, shop $SHOP_B (code VER-$STAMP), both demo.
  Shop #1 was not written to. Remove the new shop when you are finished with
  it - it is a real row in a real marketplace, and a demo shop nobody closes
  is a shop a customer can eventually be offered.
EOF
[ "$fail" -eq 0 ] || exit 1
