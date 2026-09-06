#!/usr/bin/env python3
"""Builds a real two-shop marketplace and proves the shops stay apart.

NOT A DEMO MODE AND NOT A FIXTURE LOADER. Every shop, merchant, listing,
price, stock row, rider and order below is created through the SAME public
API a real onboarding uses - the routes in SecondMerchantOnboardingTest, in
the same order - against a backend running MULTI_SHOP_PRODUCTION with
ddl-auto=validate. Nothing here reaches into the database to make something
true that the API would not have made true.

WHAT IT IS FOR. Everything up to now was verified with ONE shop: the code
paths were exercised, but a filter that narrows to "the caller's shop" cannot
be shown to work when there is only one shop for it to narrow to. This builds
the second shop and then tries, on purpose, to reach across.
"""
import json
import os
import datetime
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8090/v1"
PASSWORD = "TwoShop!2345"
STAMP = str(int(time.time()))

# Both shops sit on the customer's doorstep. Distance is not what this is
# testing, and two shops that both serve the address is what makes a
# multi-shop BASKET possible at all.
LAT, LNG = 27.162310, 83.940468

# Where the Flutter check picks up what this run built. Written last, so its
# presence also means the checks above finished.
FIXTURE_PATH = os.environ.get("TWO_SHOP_FIXTURE", "/tmp/two-shop/fixture.json")

passed, failed = [], []


def call(method, path, token=None, body=None, shop=None, idempotency=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if idempotency:
        req.add_header("Idempotency-Key", idempotency)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if shop is not None:
        req.add_header("X-Shop-Id", str(shop))
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=60) as response:
            text = response.read().decode()
            return response.status, (json.loads(text) if text.strip() else None)
    except urllib.error.HTTPError as error:
        text = error.read().decode()
        try:
            return error.code, json.loads(text)
        except json.JSONDecodeError:
            return error.code, text


def check(name, condition, detail=""):
    (passed if condition else failed).append(name)
    print(("  PASS  " if condition else "  FAIL  ") + name + (f"   [{detail}]" if detail else ""))
    return condition


def register(prefix, phone_prefix):
    email = f"{prefix}-{STAMP}@example.test"
    status, body = call("POST", "/api/auth/register", body={
        "name": prefix, "email": email,
        "phone": phone_prefix + STAMP[-8:], "password": PASSWORD})
    if status != 200:
        sys.exit(f"could not register {prefix}: {status} {body}")
    return email, body["token"], body["customerId"] if "customerId" in body else None


def login(email):
    status, body = call("POST", "/api/auth/login", body={"email": email, "password": PASSWORD})
    if status != 200:
        sys.exit(f"could not sign in {email}: {status} {body}")
    return body["token"]


def worker_login(email):
    status, body = call("POST", "/api/worker/auth/login",
                        body={"identifier": email, "password": PASSWORD})
    if status != 200:
        sys.exit(f"could not sign in worker {email}: {status} {body}")
    return body["accessToken"]


def sql(statement):
    """The two things the API deliberately has no route for.

    A platform admin cannot be created by a customer request, and a rider's
    app password lives on delivery_partners rather than on a customer. Both
    are grants, and a system that let a caller award itself either would be
    broken. So the environment sets them the way an operator would - directly
    - and everything else in this file goes through the API.
    """
    import subprocess
    subprocess.run(
        ["psql", "postgresql://gpstore:gpstore_test_password@localhost:5432/gpstore_mshop",
         "-qtAc", statement], check=True, capture_output=True)


def sql_one(statement):
    import subprocess
    out = subprocess.run(
        ["psql", "postgresql://gpstore:gpstore_test_password@localhost:5432/gpstore_mshop",
         "-qtAc", statement], check=True, capture_output=True, text=True)
    return out.stdout.strip()


print("=" * 78)
print("BUILDING TWO INDEPENDENT SHOPS")
print("=" * 78)

status, mode = call("GET", "/api/marketplace/mode")
print(f"mode: {mode}")
if not mode or not mode.get("multiShop"):
    sys.exit("this backend is not running MULTI_SHOP_PRODUCTION - nothing below would mean anything")

# ---------------------------------------------------------------- people
platform_email, _, _ = register("plat", "71")
sql(f"UPDATE customers SET role='PLATFORM_ADMIN' WHERE email='{platform_email}'")
platform = login(platform_email)

owner_a_email, _, _ = register("ownera", "72")
owner_b_email, _, _ = register("ownerb", "73")
owner_a_id = sql_one(f"SELECT id FROM customers WHERE email='{owner_a_email}'")
owner_b_id = sql_one(f"SELECT id FROM customers WHERE email='{owner_b_email}'")
sql(f"UPDATE customers SET role='ADMIN' WHERE email IN ('{owner_a_email}','{owner_b_email}')")

# ------------------------------------------------------- the shared catalogue
#
# ONE CATALOGUE, TWO SHELVES (§10). The products are central and defined once;
# what makes the shops' catalogues different is which of them each shop LISTS,
# at what price, with what stock. Giving each shop its own products table
# would be the separate-codebase-per-shop mistake in database form.
status, category = call("POST", "/api/categories", platform,
                        {"name": f"Two-shop category {STAMP}", "active": True, "gstRate": 5})
category_id = category["id"]

variants = {}
products = {}
# A BRAND PER SHELF, because "Shop by Brand" and the brand counts are browse
# surfaces like any other: a storefront offering a tile for a brand it has
# never stocked opens on an empty grid, and the number on the tile is a count
# of the shop next door's stock.
brands = {"A-only-rice": f"BrandA{STAMP}", "A-only-dal": f"BrandA{STAMP}",
          "B-only-oil": f"BrandB{STAMP}", "B-only-atta": f"BrandB{STAMP}"}
for label in ["A-only-rice", "A-only-dal", "B-only-oil", "B-only-atta"]:
    status, product = call("POST", "/api/products", platform, {
        "name": f"{label} {STAMP}", "brand": brands[label], "active": True,
        "category": {"id": category_id}})
    products[label] = product["id"]
    status, variant = call("POST", "/api/product-variants", platform, {
        "product": {"id": product["id"]}, "quantity": 1.0, "unit": "kg",
        "mrp": 100, "sellingPrice": 90, "available": True, "active": True})
    if not isinstance(variant, dict) or "id" not in variant:
        sys.exit(f"variant create failed for {label}: {status} {variant}")
    variants[label] = variant["id"]
print(f"central catalogue: {variants}")

# ------------------------------------------------------------------ SHOP A
#
# The shop that already existed. ShopBootstrap created it from STORE_*
# configuration on first boot, exactly as a real deployment's Shop #1 is -
# so this is not a shop built for the test, it is the production shop with a
# second one opened beside it.
shop_a = 1
merchant_a = sql_one("SELECT merchant_id FROM shops WHERE id = 1")
status, _ = call("POST", f"/api/platform/shops/{shop_a}/staff", platform,
                 {"customerId": int(owner_a_id), "asDefault": True})
print(f"shop A = {shop_a} under merchant {merchant_a}, owner {owner_a_id}: {status}")

# ------------------------------------------------------------------ SHOP B
#
# A DIFFERENT BUSINESS, onboarded through the platform API in the same order
# a real one would be: application, review, approval, shop, staff.
status, merchant = call("POST", "/api/platform/merchants", platform, {
    "legalName": f"Second Kirana Pvt Ltd {STAMP}", "displayName": "Second Kirana",
    "contactPhone": "9800000002", "ownerCustomerId": int(owner_b_id)})
merchant_b = merchant["id"]
for step, reason in [("PENDING_REVIEW", "papers submitted"), ("APPROVED", "papers checked")]:
    call("PUT", f"/api/platform/merchants/{merchant_b}/status", platform,
         {"status": step, "reason": reason})

status, shop = call("POST", "/api/platform/shops", platform, {
    "merchantId": merchant_b, "code": f"TWOSHOP-B-{STAMP}",
    "displayName": "Second Kirana", "latitude": LAT, "longitude": LNG,
    "maxDeliveryRadiusKm": 8, "timeZone": "Asia/Kolkata"})
shop_b = shop["id"]
call("POST", f"/api/platform/shops/{shop_b}/staff", platform,
     {"customerId": int(owner_b_id), "asDefault": True})
print(f"shop B = {shop_b} under merchant {merchant_b}, owner {owner_b_id}")

owner_a = login(owner_a_email)
owner_b = login(owner_b_email)

# ------------------------------------------- each shop stocks its own shelf
#
# DIFFERENT PRODUCTS, DIFFERENT PRICES, DIFFERENT STOCK. A lists two variants
# the other does not, and vice versa - which is what makes "shop A's catalogue"
# a meaningful phrase and lets the catalogue tests below mean something.
listings = {
    shop_a: {"A-only-rice": (60.00, 30), "A-only-dal": (120.00, 12)},
    shop_b: {"B-only-oil": (185.00, 25), "B-only-atta": (55.00, 40)},
}
inventory_ids = {}
for shop_id, token in [(shop_a, owner_a), (shop_b, owner_b)]:
    for label, (price, stock) in listings[shop_id].items():
        variant_id = variants[label]
        call("PUT", f"/api/shop/listings/{variant_id}", token, {
            "sellingPrice": price, "costPrice": price * 0.7, "mrp": price * 1.2,
            "available": True, "active": True})
        status, row = call("POST", "/api/inventory", token,
                           {"productVariant": {"id": variant_id}, "stock": stock,
                            "reservedStock": 0})
        inventory_ids[(shop_id, label)] = row["inventoryId"] if row and "inventoryId" in row else row.get("id")
print(f"inventory rows: {inventory_ids}")

# ------------------------------------------------ each shop hires its own rider
riders = {}
for shop_id, token, tag in [(shop_a, owner_a, "a"), (shop_b, owner_b, "b")]:
    email = f"rider{tag}-{STAMP}@gmail.com"
    # /api/admin/workers, not /api/delivery-partners: this is the route that
    # hires a rider AND sets the login their app signs in with, which is the
    # whole point of a rider who is going to be given a delivery.
    status, rider = call("POST", "/api/admin/workers", token, {
        "name": f"Rider {tag.upper()} {STAMP}",
        "mobile": ("85" if tag == "b" else "84") + STAMP[-8:],
        "vehicleType": "BIKE", "vehicleNumber": "UP32 AB 1234",
        "available": True, "loginEmail": email, "password": PASSWORD})
    if not isinstance(rider, dict) or "id" not in rider:
        sys.exit(f"could not hire rider {tag}: {status} {rider}")
    riders[shop_id] = {"id": rider["id"], "email": email,
                       "canSignIn": rider.get("canSignIn")}
print(f"riders: {riders}")

# ------------------------------------------- open both shops for business
for merchant_id in [merchant_a, merchant_b]:
    call("PUT", f"/api/platform/merchants/{merchant_id}/status", platform,
         {"status": "ACTIVE", "reason": "open for business"})
for shop_id in [shop_a, shop_b]:
    call("PUT", f"/api/platform/shops/{shop_id}/status", platform,
         {"status": "ACTIVE", "reason": "ready to trade"})

print()
print("=" * 78)
print("PROVING THE SHOPS ARE INDEPENDENT")
print("=" * 78)

# --------------------------------------------------------------- customer
customer_email, customer, _ = register("shopper", "74")
status, address = call("POST", "/api/addresses", customer, {
    "fullName": "Two-shop shopper", "mobileNumber": "9000000074",
    "houseNo": "1", "area": "Market Road", "city": "Gorakhpur",
    "state": "UP", "pincode": "273001", "country": "India",
    "latitude": LAT, "longitude": LNG, "defaultAddress": True})
if not isinstance(address, dict) or not (address.get("id") or address.get("addressId")):
    sys.exit(f"address create failed: {status} {address}")
address_id = address.get("id") or address.get("addressId")

# 1. ------------------------------------------------- discover both shops
status, nearby = call("GET", f"/api/marketplace/shops?lat={LAT}&lng={LNG}", customer)
found = {s["shopId"] for s in nearby}
check("1. customer discovers BOTH shops", {shop_a, shop_b} <= found,
      f"found {sorted(found)}")

# 2/3. ------------------------------ each storefront shows only its own shelf
def catalogue_of(shop_id):
    """Which of this run's four products this storefront actually offers.

    THE CUSTOMER'S OWN VIEW, not the shopkeeper's listing table. A shelf that
    is right in shop_product_variants and wrong on the storefront is the bug
    worth catching, so this reads what a shopper reads.
    """
    status, page = call("GET", "/api/products/feed?page=0&size=200", customer, shop=shop_id)
    items = page.get("content", page.get("products", page)) if isinstance(page, dict) else page
    if not isinstance(items, list):
        sys.exit(f"unexpected feed shape for shop {shop_id}: {status} {str(page)[:400]}")
    offered = set()
    for item in items:
        if not isinstance(item, dict):
            continue
        for variant in item.get("variants", []) or []:
            for label, variant_id in variants.items():
                if variant.get("id") == variant_id or variant.get("variantId") == variant_id:
                    offered.add(label)
    return offered

catalogue_a = catalogue_of(shop_a)
catalogue_b = catalogue_of(shop_b)
check("2. Shop A's storefront offers Shop A's items and NONE of Shop B's",
      catalogue_a == {"A-only-rice", "A-only-dal"}, f"offers {sorted(catalogue_a)}")
check("3. Shop B's storefront offers Shop B's items and NONE of Shop A's",
      catalogue_b == {"B-only-oil", "B-only-atta"}, f"offers {sorted(catalogue_b)}")

# 3b-3h. -------------------- and so does EVERY OTHER WAY INTO THE CATALOGUE
#
# The feed above is one door. A customer also searches, taps a brand, opens a
# bestseller tile, and follows a link straight to a product page - and each of
# those is a different query. Checking only the feed is how the feed gets
# fixed and the other five keep answering with the whole marketplace.
def ids_in(payload):
    items = payload.get("content", payload) if isinstance(payload, dict) else payload
    return {item.get("id") for item in items if isinstance(item, dict)}

def searched(shop_id, keyword):
    status, page = call("GET", f"/api/products/search/instant?keyword={keyword}&size=50",
                        customer, shop=shop_id)
    return ids_in(page if isinstance(page, (dict, list)) else {})

found_a = searched(shop_a, STAMP)
found_b = searched(shop_b, STAMP)
check("3b. search in Shop A finds A's products and none of B's",
      products["A-only-rice"] in found_a and products["B-only-oil"] not in found_a,
      f"A found {len(found_a)}")
check("3c. search in Shop B finds B's products and none of A's",
      products["B-only-oil"] in found_b and products["A-only-rice"] not in found_b,
      f"B found {len(found_b)}")

check("3d. searching Shop A for a brand only Shop B stocks returns nothing of B's",
      products["B-only-oil"] not in searched(shop_a, brands["B-only-oil"]),
      "search is the easiest door into another shop's stock")

def brand_names(shop_id):
    status, rows = call("GET", "/api/products/brands", customer, shop=shop_id)
    return {row.get("brand") for row in rows or []}

brands_a = brand_names(shop_a)
check("3e. Shop A's brand list holds its own brand and not Shop B's",
      brands["A-only-rice"] in brands_a and brands["B-only-oil"] not in brands_a,
      f"{len(brands_a)} brands")

status, brand_page = call(
    "GET", f"/api/products/brand/{brands['B-only-oil']}?size=50", customer, shop=shop_a)
check("3f. browsing Shop A by Shop B's brand shows nothing",
      products["B-only-oil"] not in ids_in(brand_page), "the tile would open on an empty grid")

status, mine = call("GET", f"/api/products/{products['A-only-rice']}", customer, shop=shop_a)
status_theirs, theirs = call(
    "GET", f"/api/products/{products['B-only-oil']}", customer, shop=shop_a)
check("3g. a product page opens for this shop's item and 404s for the other's",
      isinstance(mine, dict) and mine.get("id") == products["A-only-rice"]
      and status_theirs == 404,
      f"own {status}, other {status_theirs}")

def tile_products(shop_id):
    status, tiles = call("GET", "/api/products/bestsellers?categories=12&perCategory=8",
                         customer, shop=shop_id)
    ids = set()
    for tile in tiles or []:
        if tile.get("categoryId") == category_id:
            ids.update(tile.get("productIds") or [])
    return ids

tiles_a = tile_products(shop_a)
check("3h. the bestsellers collage in Shop A holds only A's products",
      products["A-only-rice"] in tiles_a and products["B-only-oil"] not in tiles_a,
      f"tile holds {sorted(tiles_a)}")

# 3i-3l. ------------------------ opening hours, and looking farther than home
#
# WHETHER A SHOP IS OPEN IS THE SHOP'S OWN ANSWER, from its own hours - the
# same answer checkout consults before it will take an order. A storefront
# that says OPEN and then refuses the basket is the failure these check for.
def storefront(shop_id, token=None):
    status, rows = call("GET", f"/api/marketplace/shops?lat={LAT}&lng={LNG}", token or customer)
    for row in rows or []:
        if row.get("shopId") == shop_id:
            return row
    return {}

call("PUT", "/api/admin/store/operations", owner_b,
     {"orderAcceptance": "OFF", "closureMessage": f"Stocktaking {STAMP}"})
front_a, front_b = storefront(shop_a), storefront(shop_b)
check("3i. one shop closing does not close the shop next door",
      front_a.get("acceptingOrders") is True and front_b.get("acceptingOrders") is False,
      f"A={front_a.get('acceptingOrders')} B={front_b.get('acceptingOrders')}")
check("3j. browsing stays open at a shop that has stopped taking orders",
      front_b.get("openNow") is True
      and front_b.get("closureReason") == f"Stocktaking {STAMP}",
      f"reason {front_b.get('closureReason')!r}")
call("PUT", "/api/admin/store/operations", owner_b, {"orderAcceptance": "AUTO"})

# The same festival, declared by both shops. Before store_closures belonged to
# a shop the second one was refused - by the first shop's row.
today = datetime.date.today().isoformat()
status_a, _ = call("POST", "/api/admin/store/closures", owner_a,
                   {"date": today, "reason": f"Festival {STAMP}"})
status_b, _ = call("POST", "/api/admin/store/closures", owner_b,
                   {"date": today, "reason": f"Festival {STAMP}"})
status_list, closures_a = call("GET", "/api/admin/store/closures", owner_a)
check("3k. both shops may close for the same festival, and each sees only its own",
      status_a == 200 and status_b == 200 and len(closures_a or []) == 1,
      f"A={status_a} B={status_b} A sees {len(closures_a or [])}")
call("DELETE", f"/api/admin/store/closures/{today}", owner_a)
call("DELETE", f"/api/admin/store/closures/{today}", owner_b)

status, near = call("GET", f"/api/marketplace/discovery?lat={LAT}&lng={LNG}", customer)
status, far = call("GET",
                   f"/api/marketplace/discovery?lat={LAT}&lng={LNG}&radiusKm=100000", customer)
check("3l. search farther is bounded by the server and stops at the top rung",
      near.get("radiusKm") is None and str(near.get("nextRadiusKm")) == "3"
      and float(far.get("radiusKm")) == 25.0 and far.get("nextRadiusKm") is None,
      f"default next={near.get('nextRadiusKm')} clamped={far.get('radiusKm')}")

# 4/5. ------------------------------------ price and stock move independently
def listing_price(token, variant_id):
    status, rows = call("GET", "/api/shop/listings?page=0&size=200", token)
    for row in rows or []:
        if row.get("productVariantId") == variant_id:
            return row.get("sellingPrice")
    return None

def stock_of(token, variant_id):
    status, page = call("GET", "/api/inventory?page=0&size=200", token)
    rows = page.get("content", page) if isinstance(page, dict) else page
    for row in rows or []:
        if row.get("variantId") == variant_id or row.get("productVariantId") == variant_id:
            return row.get("stock")
    return None

# Both shops list one shared variant so a price change has something to NOT
# affect. Until now their shelves did not overlap at all, which would have
# made "independent prices" trivially true.
shared = variants["A-only-rice"]
call("PUT", f"/api/shop/listings/{shared}", owner_b,
     {"sellingPrice": 71.00, "costPrice": 50.0, "mrp": 90.0, "available": True, "active": True})
call("POST", "/api/inventory", owner_b,
     {"productVariant": {"id": shared}, "stock": 7, "reservedStock": 0})

before_b_price, before_b_stock = listing_price(owner_b, shared), stock_of(owner_b, shared)
call("PUT", f"/api/shop/listings/{shared}", owner_a,
     {"sellingPrice": 44.00, "costPrice": 30.0, "mrp": 90.0, "available": True, "active": True})
after_a_price = listing_price(owner_a, shared)
after_b_price, after_b_stock = listing_price(owner_b, shared), stock_of(owner_b, shared)
check("4. Shop A's price change does not move Shop B's price",
      after_a_price == 44.00 and after_b_price == before_b_price,
      f"A={after_a_price} B={before_b_price}->{after_b_price}")

call("PUT", f"/api/shop/listings/{shared}", owner_b,
     {"sellingPrice": 99.00, "costPrice": 60.0, "mrp": 120.0, "available": True, "active": True})
check("5. Shop B's price change does not move Shop A's price",
      listing_price(owner_a, shared) == 44.00 and listing_price(owner_b, shared) == 99.00,
      f"A={listing_price(owner_a, shared)} B={listing_price(owner_b, shared)}")

# 6/7. ------------------------- one basket from two shops becomes two orders
# Query parameters, not a body - which is what CartRepository.addToCart sends.
add_a = call("POST", f"/api/carts/add?variantId={variants['A-only-dal']}&quantity=2",
             customer, shop=shop_a)
add_b = call("POST", f"/api/carts/add?variantId={variants['B-only-oil']}&quantity=1",
             customer, shop=shop_b)
print(f"  add to cart: A={add_a[0]} B={add_b[0]}")
status, cart = call("GET", "/api/carts/mine", customer)
cart_shops = {line["shopId"] for line in cart["items"]}
check("6. one basket holds lines from both shops",
      cart_shops == {shop_a, shop_b},
      f"lines from {sorted(cart_shops)}, labels {[s.get('shopName') for s in cart.get('shops', [])]}")

# ONE KEY PER CHECKOUT ATTEMPT, which the backend requires and the app sends.
status, placed = call("POST", "/api/orders/place", customer,
                      {"addressId": address_id, "paymentMethod": "COD"},
                      idempotency=f"twoshop-{STAMP}")
if status != 200:
    print(f"  checkout refused: {status} {placed}")
group_id = (placed or {}).get("orderGroupId")
shop_orders = (placed or {}).get("shopOrders", [])
check("7. checkout creates ONE group with a SEPARATE order per shop",
      status == 200 and len(shop_orders) == 2
      and {o["shopId"] for o in shop_orders} == {shop_a, shop_b},
      f"group={group_id} orders={[(o['shopId'], o['orderNumber']) for o in shop_orders]}")

order_in = {o["shopId"]: o["orderId"] for o in shop_orders}

# 8/9. ------------------------------ each merchant sees only their own order
def order_ids_for(token):
    status, page = call("GET", "/api/orders/admin/all?page=0&size=50", token)
    rows = page.get("content", page) if isinstance(page, dict) else (page or [])
    return {row["orderId"] for row in rows}

seen_a, seen_b = order_ids_for(owner_a), order_ids_for(owner_b)
check("8. Shop A's merchant sees A's order and NOT B's",
      order_in.get(shop_a) in seen_a and order_in.get(shop_b) not in seen_a,
      f"A sees {len(seen_a)} orders")
check("9. Shop B's merchant sees B's order and NOT A's",
      order_in.get(shop_b) in seen_b and order_in.get(shop_a) not in seen_b,
      f"B sees {len(seen_b)} orders")

# 15. ------------------------------ the customer's history names the shop
status, history = call("GET", "/api/orders/my-orders?page=0&size=20", customer)
rows = history.get("content", history) if isinstance(history, dict) else history
named = {row["orderId"]: row.get("shopName") for row in rows}
check("15. order history names the shop each order came from",
      all(named.get(order_id) for order_id in order_in.values())
      and len(set(named[o] for o in order_in.values())) == 2,
      f"{[named.get(o) for o in order_in.values()]}")

status, group = call("GET", f"/api/orders/groups/{group_id}", customer)
check("15b. the group screen names both shops",
      len({o.get("shopName") for o in group.get("shopOrders", [])}) == 2,
      f"{[o.get('shopName') for o in group.get('shopOrders', [])]}")

# 10/11/16. --------------------------- riders see only their own shop's work
#
# Each shop confirms and dispatches its OWN order, assigns its OWN rider, and
# the riders are then asked what they have to do.
def advance(token, order_id, statuses):
    for state in statuses:
        call("PUT", f"/api/orders/{order_id}/status?status={state}", token)

advance(owner_a, order_in[shop_a], ["CONFIRMED", "PACKING", "READY_TO_DISPATCH"])
advance(owner_b, order_in[shop_b], ["CONFIRMED", "PACKING", "READY_TO_DISPATCH"])
assign_a = call("POST", f"/api/deliveries/assign?orderId={order_in[shop_a]}"
                        f"&deliveryPartnerId={riders[shop_a]['id']}", owner_a)
assign_b = call("POST", f"/api/deliveries/assign?orderId={order_in[shop_b]}"
                        f"&deliveryPartnerId={riders[shop_b]['id']}", owner_b)
print(f"  assignment A={assign_a[0]} B={assign_b[0]}")

rider_a = worker_login(riders[shop_a]["email"])
rider_b = worker_login(riders[shop_b]["email"])
status, round_a = call("GET", "/api/deliveries/my-assignments", rider_a)
status, round_b = call("GET", "/api/deliveries/my-assignments", rider_b)
orders_a = {row.get("orderId") for row in (round_a or [])}
orders_b = {row.get("orderId") for row in (round_b or [])}
check("10. Shop A's rider has A's delivery and not B's",
      order_in[shop_a] in orders_a and order_in[shop_b] not in orders_a, f"{orders_a}")
check("11. Shop B's rider has B's delivery and not A's",
      order_in[shop_b] in orders_b and order_in[shop_a] not in orders_b, f"{orders_b}")

# 16. -------------------------------- delivery status reaches the order
delivery_a = (round_a or [{}])[0].get("deliveryId")
if delivery_a:
    for state in ["PACKED", "PICKED_UP", "OUT_FOR_DELIVERY"]:
        call("PUT", f"/api/deliveries/{delivery_a}/status?status={state}", rider_a)
status, order_a_after = call("GET", f"/api/orders/{order_in[shop_a]}", owner_a)
status, order_b_after = call("GET", f"/api/orders/{order_in[shop_b]}", owner_b)
check("16. a rider's move propagates to their own order and not the other shop's",
      (order_a_after or {}).get("orderStatus") == "OUT_FOR_DELIVERY"
      and (order_b_after or {}).get("orderStatus") != "OUT_FOR_DELIVERY",
      f"A={(order_a_after or {}).get('orderStatus')} B={(order_b_after or {}).get('orderStatus')}")

# 14. ------------------------------------- the platform can see both, legitimately
status, overview = call("GET", "/api/platform/overview?days=30", platform)
overview_shops = {line["shopId"] for line in (overview or {}).get("shops", [])}
status, all_shops = call("GET", "/api/platform/shops", platform)
status, all_merchants = call("GET", "/api/platform/merchants", platform)
check("14. platform admin legitimately sees both shops and both merchants",
      {shop_a, shop_b} <= {s["id"] for s in (all_shops or [])}
      and {merchant_a and int(merchant_a), merchant_b} <= {m["id"] for m in (all_merchants or [])}
      and {shop_a, shop_b} <= overview_shops,
      f"overview covers {sorted(overview_shops)}")

print()
print("=" * 78)
print("CROSS-TENANT ATTACKS - every one of these must be refused")
print("=" * 78)

def denied(name, method, path, token, body=None, shop=None):
    """Refused is refused, whatever shape the refusal takes.

    404 is the usual one and is deliberate: another shop's row is answered as
    "not found" rather than "forbidden", so a guessed id cannot confirm that
    somebody else's order exists. 403 is the platform surface saying no. 401
    is the tenant filter refusing a credential that resolves to no shop at
    all. 400 is a domain rule ("that rider works for a different shop").

    405 counts too, and is the strongest of them: the operation does not
    exist. DELETE /api/orders/{id} has no route, so nobody can delete
    anybody's order - which is a better answer than a well-guarded one.

    The one status that would be a failure is 200.
    """
    status, payload = call(method, path, token, body, shop=shop)
    refused = status in (400, 401, 403, 404, 405, 409)
    check(name, refused, f"HTTP {status}")
    return refused

# 12/13. every vector the brief names, in both directions.
b_variant = variants["B-only-oil"]
a_variant = variants["A-only-dal"]
b_inventory = inventory_ids.get((shop_b, "B-only-oil"))
a_inventory = inventory_ids.get((shop_a, "A-only-dal"))

denied("12a. A names B's shop_id on a request", "GET", "/api/shop/profile", owner_a, shop=shop_b)
denied("12b. A reads B's order by id", "GET", f"/api/orders/{order_in[shop_b]}", owner_a)
denied("12c. A reads B's inventory row by id", "GET", f"/api/inventory/{b_inventory}", owner_a)
denied("12d. A UPDATES B's inventory row by id", "PUT", f"/api/inventory/{b_inventory}", owner_a,
       {"stock": 9999, "reservedStock": 0})
denied("12e. A restocks B's inventory row", "PUT", f"/api/inventory/{b_inventory}/restock?quantity=500",
       owner_a)
denied("12f. A moves B's order", "PUT",
       f"/api/orders/{order_in[shop_b]}/status?status=CANCELLED", owner_a)
denied("12g. A reads B's rider", "GET", f"/api/admin/workers/{riders[shop_b]['id']}", owner_a)
denied("12h. A assigns B's rider to A's order", "POST",
       f"/api/deliveries/assign?orderId={order_in[shop_a]}"
       f"&deliveryPartnerId={riders[shop_b]['id']}", owner_a)
denied("12i. A reads the platform's merchant list", "GET", "/api/platform/merchants", owner_a)
denied("12j. A moves B's merchant", "PUT", f"/api/platform/merchants/{merchant_b}/status", owner_a,
       {"status": "SUSPENDED", "reason": "not mine to suspend"})

denied("13a. B names A's shop_id on a request", "GET", "/api/shop/profile", owner_b, shop=shop_a)
denied("13b. B reads A's order by id", "GET", f"/api/orders/{order_in[shop_a]}", owner_b)
denied("13c. B reads A's inventory row by id", "GET", f"/api/inventory/{a_inventory}", owner_b)
denied("13d. B UPDATES A's inventory row by id", "PUT", f"/api/inventory/{a_inventory}", owner_b,
       {"stock": 9999, "reservedStock": 0})
denied("13e. B DELETES A's order", "DELETE", f"/api/orders/{order_in[shop_a]}", owner_b)
denied("13f. B reads A's rider", "GET", f"/api/admin/workers/{riders[shop_a]['id']}", owner_b)
denied("13g. B moves A's delivery", "PUT",
       f"/api/deliveries/{delivery_a}/status?status=DELIVERED", owner_b)

# Riders, who are shop-scoped too (W4).
denied("12k. A's rider opens B's order", "GET", f"/api/worker/orders/{order_in[shop_b]}", rider_a)
denied("13h. B's rider opens A's order", "GET", f"/api/worker/orders/{order_in[shop_a]}", rider_b)
denied("13i. B's rider moves A's delivery", "PUT",
       f"/api/deliveries/{delivery_a}/status?status=DELIVERED", rider_b)
denied("12l. A's rider reads the platform surface", "GET", "/api/platform/shops", rider_a)

# And the customer, who owns their own orders but not anybody else's.
denied("12m. a customer reads a merchant's order list", "GET",
       "/api/orders/admin/all?page=0&size=10", customer)
denied("12n. a customer reads a shop's earnings", "GET", "/api/shop/earnings", customer)

print()
print("=" * 78)
print(f"PASSED {len(passed)}    FAILED {len(failed)}")
for name in failed:
    print("  FAILED: " + name)
print("=" * 78)
summary = {"shopA": shop_a, "shopB": shop_b, "merchantA": merchant_a,
           "merchantB": merchant_b, "group": group_id,
           "orders": order_in, "riders": {k: v["id"] for k, v in riders.items()}}
print(json.dumps(summary))

# ------------------------------------------------------- for the Flutter run
#
# WHAT THIS FILE IS FOR. The real app has to be driven against this same
# marketplace - two shops that exist, with shelves that differ - and it cannot
# be driven against ids somebody typed into a Dart file, because this database
# is rebuilt from empty on every run. So the fixture writes down what it built
# and the Flutter check reads it.
#
# It is NOT a mock or a seed: every id in here was created a moment ago
# through the same public API a real onboarding uses, and the app then talks
# to the same server.
fixture = {
    "baseUrl": BASE,
    "password": PASSWORD,
    "lat": LAT, "lng": LNG,
    "shopA": shop_a, "shopB": shop_b,
    "ownerA": owner_a_email, "ownerB": owner_b_email,
    "platform": platform_email,
    "riderA": riders[shop_a]["email"], "riderB": riders[shop_b]["email"],
    "categoryId": category_id,
    "productsA": {label: products[label] for label in listings[shop_a]},
    "productsB": {label: products[label] for label in listings[shop_b]},
    "variantsA": {label: variants[label] for label in listings[shop_a]},
    "variantsB": {label: variants[label] for label in listings[shop_b]},
    "brandA": brands["A-only-rice"], "brandB": brands["B-only-oil"],
    "stamp": STAMP,

    # NAMED SEPARATELY because checks 4/5 above left A-only-rice on BOTH
    # shelves on purpose - two shops listing the same catalogue item at
    # different prices is what makes "independent prices" a real claim rather
    # than a trivial one. So an "only A sells this" assertion has to use the
    # item that really is only A's.
    "exclusiveAProduct": products["A-only-dal"], "exclusiveAVariant": variants["A-only-dal"],
    "exclusiveBProduct": products["B-only-oil"], "exclusiveBVariant": variants["B-only-oil"],
    "sharedProduct": products["A-only-rice"], "sharedVariant": variants["A-only-rice"],

    # The order each shop already packed, so the rider check can say which
    # round a delivery belongs to by name rather than by counting rows.
    "orderA": order_in[shop_a], "orderB": order_in[shop_b],
}
with open(FIXTURE_PATH, "w") as handle:
    json.dump(fixture, handle, indent=2)
print(f"fixture written to {FIXTURE_PATH}")

sys.exit(1 if failed else 0)
