#!/usr/bin/env bash
# One-shot seed/cleanup for one precisely named synthetic batch.
# This is never called by deploy.sh or by ordinary application startup.
set -Eeuo pipefail
umask 077

DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/gp-store}"
COMPOSE_DIR="${DEPLOY_ROOT}/backend"
COMPOSE_FILE="${COMPOSE_DIR}/docker-compose.yml"
BATCH_ID="MARKETPLACE_TEST_100_SHOPS_V1"
OPERATION="${GPSTORE_TEST_DATA_OPERATION:-}"
TARGET_SHA="${GPSTORE_TEST_DATA_TARGET_SHA:-}"
CONFIRMATION="${GPSTORE_TEST_DATA_CONFIRMATION:-}"
PUBLIC_VERSION_URL="${PUBLIC_VERSION_URL:-https://api.gpstore.co.in/v1/api/version}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
compose() { docker compose -f "$COMPOSE_FILE" --project-directory "$COMPOSE_DIR" "$@"; }

[[ "$CONFIRMATION" == "$BATCH_ID" ]] || die "Exact batch confirmation is required."
[[ "$OPERATION" == "INSPECT" || "$OPERATION" == "SEED" || "$OPERATION" == "CLEANUP" ]] || die "Operation must be INSPECT, SEED or CLEANUP."
[[ "$TARGET_SHA" =~ ^[0-9a-f]{40}$ ]] || die "Target SHA must be a full lowercase commit SHA."
[[ -f "$COMPOSE_FILE" ]] || die "Hostinger Compose file was not found."

cd "$DEPLOY_ROOT"
git fetch origin --prune
[[ "$(git rev-parse HEAD)" == "$TARGET_SHA" ]] || die "Hostinger checkout does not match the requested SHA."
[[ "$(git rev-parse origin/main)" == "$TARGET_SHA" ]] || die "Requested SHA is not current origin/main."

version="$(curl -fsS --max-time 20 "$PUBLIC_VERSION_URL")" || die "Public production version endpoint is unavailable."
python3 - "$version" "$TARGET_SHA" <<'PY'
import json, sys
body = json.loads(sys.argv[1])
expected = sys.argv[2]
if body.get("environment") != "production":
    raise SystemExit("Public API does not identify as production")
if body.get("gitCommit") != expected or body.get("binaryGitCommit") != expected:
    raise SystemExit("Public API source/binary SHA does not match requested commit")
PY

runtime="$(compose exec -T backend curl -fsS http://127.0.0.1:8081/v1/api/version)" \
  || die "Could not read the running Hostinger backend identity."
python3 - "$runtime" "$TARGET_SHA" <<'PY'
import json, sys
body = json.loads(sys.argv[1])
expected = sys.argv[2]
if body.get("environment") != "production":
    raise SystemExit("Hostinger backend does not identify as production")
if body.get("gitCommit") != expected or body.get("binaryGitCommit") != expected:
    raise SystemExit("Hostinger running backend source/binary SHA mismatch")
PY

# Compose supplies the existing production DB, Redis, secret mount and network.
# The dedicated profile disables HTTP and scheduled jobs; the runner exits after
# the single explicit operation. Ordinary backend startup never activates it.
#
# Select the immutable image that the verified live container is already
# running. Without these exports Compose falls back to :latest/unknown and may
# rebuild a different jar from the checkout. VersionGuard must see the same SHA
# both inside that jar and in the one-shot process environment.
TARGET_IMAGE="gp-store-backend:$TARGET_SHA"
docker image inspect "$TARGET_IMAGE" >/dev/null \
  || die "The verified deployed backend image $TARGET_IMAGE is not present."
export BACKEND_IMAGE_TAG="$TARGET_SHA"
export GIT_COMMIT="$TARGET_SHA"
compose run --rm --no-deps \
  -e "GIT_COMMIT=$TARGET_SHA" \
  -e "GPSTORE_TEST_DATA_CONFIRMATION=$BATCH_ID" \
  -e "GPSTORE_TEST_DATA_ALLOW_PRODUCTION=true" \
  -e "GPSTORE_TEST_DATA_EXPECTED_SHA=$TARGET_SHA" \
  backend \
  --spring.main.web-application-type=none \
  --spring.profiles.active=prod,marketplace-test-data-cli \
  --gpstore.synthetic-data.action="$OPERATION" \
  --gpstore.synthetic-data.batch="$BATCH_ID" \
  --gpstore.synthetic-data.expected-commit="$TARGET_SHA"

if [[ "$OPERATION" == "CLEANUP" ]]; then
  echo "Exact synthetic batch cleanup completed."
  exit 0
fi

# Read the same configured seed anchor without disclosing any DB credential.
first_shop_code="$(compose exec -T backend sh -c 'printf "%s" "${PLATFORM_FIRST_SHOP_CODE:-SHOP-1}"')"
[[ "$first_shop_code" =~ ^[A-Za-z0-9_-]+$ ]] || die "Configured first-shop code is not a safe identifier."
anchor_sql="SELECT latitude, longitude FROM shops WHERE code = '$first_shop_code' AND latitude IS NOT NULL AND longitude IS NOT NULL"
anchor="$(compose exec -T postgres sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "$1"' sh "$anchor_sql")"
IFS='|' read -r anchor_lat anchor_lng <<< "$anchor"
[[ "$anchor_lat" =~ ^-?[0-9]+([.][0-9]+)?$ && "$anchor_lng" =~ ^-?[0-9]+([.][0-9]+)?$ ]] || die "Configured marketplace anchor is unavailable."
discovery="$(compose exec -T backend curl -fsS --max-time 20 \
  "http://127.0.0.1:8081/v1/api/marketplace/discovery?lat=${anchor_lat}&lng=${anchor_lng}&radiusKm=999999")" \
  || die "Read-only marketplace discovery probe failed."
max_radius="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("maxRadiusKm") or "")' "$discovery")"
[[ "$max_radius" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "Production radius ladder could not be read."

read -r -d '' verification_sql <<'SQL' || true
WITH visible_distances AS (
    SELECT s.id,
           6371 * acos(least(1.0, greatest(-1.0,
               cos(radians(:anchor_lat)) * cos(radians(s.latitude))
               * cos(radians(s.longitude) - radians(:anchor_lng))
               + sin(radians(:anchor_lat)) * sin(radians(s.latitude))))) AS distance_km
      FROM shops s
     WHERE s.active = TRUE AND s.status IN ('ACTIVE','PAUSED')
       AND s.latitude IS NOT NULL AND s.longitude IS NOT NULL
), controlled_distances AS (
    SELECT id, distance_km
      FROM visible_distances
     WHERE id IN (
           SELECT id FROM shops
            WHERE is_demo = TRUE AND code ~ '^MKT100V1-SHOP-[0-9]{3}$'
     )
), batch_category_counts AS (
    SELECT c.name AS category, count(DISTINCT spv.shop_id) AS shop_count
      FROM shop_product_variants spv
      JOIN product_variants v ON v.id = spv.product_variant_id
      JOIN products p ON p.id = v.product_id
      JOIN categories c ON c.id = p.category_id
     WHERE p.is_test_data = TRUE AND p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1'
     GROUP BY c.name
), batch_shop_categories AS (
    SELECT spv.shop_id, count(DISTINCT p.category_id) AS category_count
      FROM shop_product_variants spv
      JOIN product_variants v ON v.id = spv.product_variant_id
      JOIN products p ON p.id = v.product_id
     WHERE p.is_test_data = TRUE AND p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1'
     GROUP BY spv.shop_id
)
SELECT json_build_object(
      'database', current_database(),
      'database_host', inet_server_addr()::text,
      'total_shops', (SELECT count(*) FROM shops),
      'non_batch_shops', (SELECT count(*) FROM shops WHERE code IS NULL OR code !~ '^MKT100V1-SHOP-[0-9]{3}$'),
      'merchants', (SELECT count(*) FROM merchants WHERE is_demo = TRUE AND left(legal_name, length('[MARKETPLACE_TEST_100_SHOPS_V1] Merchant ')) = '[MARKETPLACE_TEST_100_SHOPS_V1] Merchant '),
      'multi_shop_merchants', (SELECT count(*) FROM (SELECT merchant_id FROM shops WHERE is_demo = TRUE AND code ~ '^MKT100V1-SHOP-[0-9]{3}$' GROUP BY merchant_id HAVING count(*) = 2) grouped),
      'shops', (SELECT count(*) FROM shops WHERE is_demo = TRUE AND code ~ '^MKT100V1-SHOP-[0-9]{3}$'),
      'unique_controlled_shop_ids', (SELECT count(DISTINCT id) FROM shops WHERE is_demo = TRUE AND code ~ '^MKT100V1-SHOP-[0-9]{3}$'),
      'unique_controlled_shop_names', (SELECT count(DISTINCT display_name) FROM shops WHERE is_demo = TRUE AND code ~ '^MKT100V1-SHOP-[0-9]{3}$'),
      'duplicate_controlled_shops', (SELECT count(*) - count(DISTINCT display_name) FROM shops WHERE is_demo = TRUE AND code ~ '^MKT100V1-SHOP-[0-9]{3}$'),
      'active_shops', (SELECT count(*) FROM shops WHERE is_demo = TRUE AND code ~ '^MKT100V1-SHOP-[0-9]{3}$' AND active = TRUE AND status = 'ACTIVE'),
      'approved_shops', (SELECT count(*) FROM shops s JOIN merchants m ON m.id = s.merchant_id WHERE s.is_demo = TRUE AND s.code ~ '^MKT100V1-SHOP-[0-9]{3}$' AND m.status IN ('APPROVED','ACTIVE')),
      'valid_merchant_relationships', (SELECT count(*) FROM shops s JOIN merchants m ON m.id = s.merchant_id WHERE s.is_demo = TRUE AND s.code ~ '^MKT100V1-SHOP-[0-9]{3}$' AND m.is_demo = TRUE AND left(m.legal_name, length('[MARKETPLACE_TEST_100_SHOPS_V1] Merchant ')) = '[MARKETPLACE_TEST_100_SHOPS_V1] Merchant '),
      'total_active_shops', (SELECT count(*) FROM shops WHERE active = TRUE AND status = 'ACTIVE'),
      'customer_visible_shops', (SELECT count(*) FROM shops WHERE is_demo = TRUE AND code ~ '^MKT100V1-SHOP-[0-9]{3}$' AND active = TRUE AND status IN ('ACTIVE','PAUSED')),
      'total_customer_visible_shops', (SELECT count(*) FROM shops WHERE active = TRUE AND status IN ('ACTIVE','PAUSED')),
      'shops_with_valid_coordinates', (SELECT count(*) FROM shops WHERE is_demo = TRUE AND code ~ '^MKT100V1-SHOP-[0-9]{3}$' AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180),
      'shops_inside_configured_max_radius_from_anchor', (SELECT count(*) FROM shops s WHERE s.is_demo = TRUE AND s.code ~ '^MKT100V1-SHOP-[0-9]{3}$' AND s.active = TRUE AND s.status IN ('ACTIVE','PAUSED') AND s.latitude IS NOT NULL AND s.longitude IS NOT NULL AND 6371 * acos(least(1.0, greatest(-1.0, cos(radians(:anchor_lat)) * cos(radians(s.latitude)) * cos(radians(s.longitude) - radians(:anchor_lng)) + sin(radians(:anchor_lat)) * sin(radians(s.latitude))))) <= :max_radius::double precision),
      'shops_excluded_by_distance_from_anchor', (SELECT count(*) FROM shops s WHERE s.is_demo = TRUE AND s.code ~ '^MKT100V1-SHOP-[0-9]{3}$' AND s.active = TRUE AND s.status IN ('ACTIVE','PAUSED') AND s.latitude IS NOT NULL AND s.longitude IS NOT NULL AND 6371 * acos(least(1.0, greatest(-1.0, cos(radians(:anchor_lat)) * cos(radians(s.latitude)) * cos(radians(s.longitude) - radians(:anchor_lng)) + sin(radians(:anchor_lat)) * sin(radians(s.latitude))))) > :max_radius::double precision),
      'shops_excluded_by_status_or_active', (SELECT count(*) FROM shops WHERE is_demo = TRUE AND code ~ '^MKT100V1-SHOP-[0-9]{3}$' AND NOT (active = TRUE AND status IN ('ACTIVE','PAUSED'))),
      'shops_order_acceptance_on', (SELECT count(*) FROM shops s JOIN store_operations_settings o ON o.shop_id = s.id WHERE s.is_demo = TRUE AND s.code ~ '^MKT100V1-SHOP-[0-9]{3}$' AND o.order_acceptance = 'ON' AND s.active = TRUE AND s.status = 'ACTIVE'),
      'merchants_not_trading', (SELECT count(*) FROM merchants WHERE is_demo = TRUE AND left(legal_name, length('[MARKETPLACE_TEST_100_SHOPS_V1] Merchant ')) = '[MARKETPLACE_TEST_100_SHOPS_V1] Merchant ' AND status <> 'ACTIVE'),
      'products', (SELECT count(*) FROM products WHERE is_test_data = TRUE AND data_source = 'MARKETPLACE_TEST_100_SHOPS_V1'),
      'variants', (SELECT count(*) FROM product_variants v JOIN products p ON p.id = v.product_id WHERE p.is_test_data = TRUE AND p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1'),
      'listings', (SELECT count(*) FROM shop_product_variants spv JOIN product_variants v ON v.id = spv.product_variant_id JOIN products p ON p.id = v.product_id WHERE p.is_test_data = TRUE AND p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1'),
      'buy_online', (SELECT count(*) FROM shop_product_variants spv JOIN product_variants v ON v.id = spv.product_variant_id JOIN products p ON p.id = v.product_id WHERE p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' AND spv.commerce_mode = 'ONLINE_PURCHASE'),
      'visit_to_buy', (SELECT count(*) FROM shop_product_variants spv JOIN product_variants v ON v.id = spv.product_variant_id JOIN products p ON p.id = v.product_id WHERE p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' AND spv.commerce_mode = 'VISIT_TO_BUY'),
      'service_at_shop', (SELECT count(*) FROM shop_product_variants spv JOIN product_variants v ON v.id = spv.product_variant_id JOIN products p ON p.id = v.product_id WHERE p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' AND spv.commerce_mode = 'SERVICE_AT_SHOP'),
      'shops_with_buy_online', (SELECT count(DISTINCT s.id) FROM shops s JOIN shop_product_variants spv ON spv.shop_id = s.id JOIN product_variants v ON v.id = spv.product_variant_id JOIN products p ON p.id = v.product_id WHERE s.code ~ '^MKT100V1-SHOP-[0-9]{3}$' AND p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' AND spv.commerce_mode = 'ONLINE_PURCHASE'),
      'shops_with_visit_to_buy', (SELECT count(DISTINCT s.id) FROM shops s JOIN shop_product_variants spv ON spv.shop_id = s.id JOIN product_variants v ON v.id = spv.product_variant_id JOIN products p ON p.id = v.product_id WHERE s.code ~ '^MKT100V1-SHOP-[0-9]{3}$' AND p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' AND spv.commerce_mode = 'VISIT_TO_BUY'),
      'shops_with_service_at_shop', (SELECT count(DISTINCT s.id) FROM shops s JOIN shop_product_variants spv ON spv.shop_id = s.id JOIN product_variants v ON v.id = spv.product_variant_id JOIN products p ON p.id = v.product_id WHERE s.code ~ '^MKT100V1-SHOP-[0-9]{3}$' AND p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' AND spv.commerce_mode = 'SERVICE_AT_SHOP'),
      'category_shop_counts', (SELECT coalesce(json_object_agg(category, shop_count), '{}'::json) FROM batch_category_counts),
      'cross_category_shops', (SELECT count(*) FROM batch_shop_categories WHERE category_count <> 1),
      'radius_counts', json_build_object(
          '8_km', (SELECT count(*) FROM visible_distances WHERE distance_km <= 8),
          '20_km', (SELECT count(*) FROM visible_distances WHERE distance_km <= 20),
          '50_km', (SELECT count(*) FROM visible_distances WHERE distance_km <= 50),
          '100_km', (SELECT count(*) FROM visible_distances WHERE distance_km <= 100),
          '500_km', (SELECT count(*) FROM visible_distances WHERE distance_km <= 500)
      ),
      'controlled_radius_counts', json_build_object(
          '8_km', (SELECT count(*) FROM controlled_distances WHERE distance_km <= 8),
          '20_km', (SELECT count(*) FROM controlled_distances WHERE distance_km <= 20),
          '50_km', (SELECT count(*) FROM controlled_distances WHERE distance_km <= 50),
          '100_km', (SELECT count(*) FROM controlled_distances WHERE distance_km <= 100),
          '500_km', (SELECT count(*) FROM controlled_distances WHERE distance_km <= 500)
      ),
      'image_products', (SELECT count(DISTINCT p.id) FROM products p JOIN product_variants v ON v.product_id = p.id LEFT JOIN product_images pi ON pi.product_id = p.id WHERE p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' AND (NULLIF(v.image_url, '') IS NOT NULL OR pi.id IS NOT NULL)),
      'undersized_shops', (SELECT count(*) FROM (SELECT s.id FROM shops s LEFT JOIN shop_product_variants spv ON spv.shop_id = s.id LEFT JOIN product_variants v ON v.id = spv.product_variant_id LEFT JOIN products p ON p.id = v.product_id AND p.is_test_data = TRUE AND p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' WHERE s.is_demo = TRUE AND s.code ~ '^MKT100V1-SHOP-[0-9]{3}$' GROUP BY s.id HAVING count(p.id) < 8) small)
    )::text;
SQL
metrics="$(compose exec -T postgres sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v anchor_lat="$2" -v anchor_lng="$3" -v max_radius="$4" -tA -c "$1"' sh "$verification_sql" "$anchor_lat" "$anchor_lng" "$max_radius")"
if [[ "$OPERATION" == "SEED" ]]; then
  python3 - "$metrics" <<'PY'
import json, sys
m = json.loads(sys.argv[1])
expected_synthetic = 100
expected_listings = expected_synthetic * 12
assert expected_synthetic > 0, m
assert m["total_shops"] == m["non_batch_shops"] + expected_synthetic, m
assert m["merchants"] == 95 and m["multi_shop_merchants"] == 5, m
assert m["shops"] == expected_synthetic, m
assert m["unique_controlled_shop_ids"] == expected_synthetic, m
assert m["unique_controlled_shop_names"] == expected_synthetic, m
assert m["duplicate_controlled_shops"] == 0, m
assert m["products"] == expected_listings and m["variants"] == expected_listings and m["listings"] == expected_listings, m
assert m["buy_online"] > 0 and m["visit_to_buy"] > 0 and m["service_at_shop"] > 0, m
assert m["undersized_shops"] == 0, m
assert m["image_products"] == 0, m
assert m["customer_visible_shops"] == expected_synthetic, m
assert m["active_shops"] == expected_synthetic, m
assert m["approved_shops"] == expected_synthetic, m
assert m["valid_merchant_relationships"] == expected_synthetic, m
assert m["shops_inside_configured_max_radius_from_anchor"] == expected_synthetic, m
assert m["shops_order_acceptance_on"] == expected_synthetic, m
assert m["merchants_not_trading"] == 0, m
assert m["cross_category_shops"] == 0, m
for category in ("Test Marketplace - Grocery", "Test Marketplace - Mobile and Electronics",
                 "Test Marketplace - Hardware and Electrical", "Test Marketplace - Clothing and Fashion",
                 "Test Marketplace - Footwear", "Test Marketplace - Restaurant and Food",
                 "Test Marketplace - Health and Medical Test Supplies",
                 "Test Marketplace - Automotive Parts", "Test Marketplace - Furniture and Home",
                 "Test Marketplace - Jewellery and Accessories",
                 "Test Marketplace - Mobile and Electronics Repair",
                 "Test Marketplace - Tractor and Agricultural Parts"):
    assert m["category_shop_counts"].get(category, 0) > 0, (category, m)
r = m["controlled_radius_counts"]
assert r == {"8_km": 60, "20_km": 85, "50_km": 95,
             "100_km": 100, "500_km": 100}, m
print(json.dumps(m, sort_keys=True))
PY
else
  python3 - "$metrics" "$anchor_lat" "$anchor_lng" "$max_radius" <<'PY'
import json, sys
print(json.dumps({"counts": json.loads(sys.argv[1]), "discovery_anchor": {
    "latitude": float(sys.argv[2]), "longitude": float(sys.argv[3]),
    "max_radius_km": float(sys.argv[4])}}, sort_keys=True))
PY
fi

# Exercise the serialized production API pages using the same configured
# marketplace anchor. Only compact counts/ids are emitted to the workflow log.
api_base="http://127.0.0.1:8081/v1/api/marketplace"
shops_pages="$(mktemp)"
trap 'rm -f "$shops_pages"' EXIT
page=0
while :; do
  body="$(compose exec -T backend curl -fsS --max-time 20 \
    "$api_base/shops/page?lat=${anchor_lat}&lng=${anchor_lng}&page=${page}&size=20")"
  printf '%s\n' "$body" >> "$shops_pages"
  has_next="$(python3 -c 'import json,sys; print(str(bool(json.loads(sys.argv[1]).get("hasNext"))).lower())' "$body")"
  [[ "$has_next" == "true" ]] || break
  page=$((page + 1))
  (( page < 100 )) || die "Marketplace shop pagination did not terminate."
done
buy_page0="$(compose exec -T backend curl -fsS --max-time 20 "$api_base/feed?lat=${anchor_lat}&lng=${anchor_lng}&mode=ONLINE_PURCHASE&page=0&size=12")"
buy_page1="$(compose exec -T backend curl -fsS --max-time 20 "$api_base/feed?lat=${anchor_lat}&lng=${anchor_lng}&mode=ONLINE_PURCHASE&page=1&size=12")"
visit_page0="$(compose exec -T backend curl -fsS --max-time 20 "$api_base/feed?lat=${anchor_lat}&lng=${anchor_lng}&mode=VISIT_TO_BUY&page=0&size=12")"
visit_page1="$(compose exec -T backend curl -fsS --max-time 20 "$api_base/feed?lat=${anchor_lat}&lng=${anchor_lng}&mode=VISIT_TO_BUY&page=1&size=12")"
service_page0="$(compose exec -T backend curl -fsS --max-time 20 "$api_base/feed?lat=${anchor_lat}&lng=${anchor_lng}&mode=SERVICE_AT_SHOP&page=0&size=12")"
service_page1="$(compose exec -T backend curl -fsS --max-time 20 "$api_base/feed?lat=${anchor_lat}&lng=${anchor_lng}&mode=SERVICE_AT_SHOP&page=1&size=12")"
python3 - "$OPERATION" "$metrics" "$shops_pages" "$buy_page0" "$buy_page1" "$visit_page0" "$visit_page1" "$service_page0" "$service_page1" <<'PY'
import json, sys
operation = sys.argv[1]
metrics = json.loads(sys.argv[2])
with open(sys.argv[3], encoding="utf-8") as handle:
    shop_pages = [json.loads(line) for line in handle if line.strip()]
buy0, buy1, visit0, visit1, service0, service1 = map(json.loads, sys.argv[4:])
assert shop_pages and all(isinstance(item.get("shops"), list) for item in shop_pages)
assert [item["page"] for item in shop_pages] == list(range(len(shop_pages)))
shop_ids = [row["shopId"] for item in shop_pages for row in item["shops"]]
assert len(shop_ids) == len(set(shop_ids)), "nearby shop pages overlap"
assert all(item["size"] <= 20 and isinstance(item["hasNext"], bool) for item in shop_pages)
assert shop_pages[-1]["hasNext"] is False
assert shop_pages[0]["totalElements"] == len(shop_ids)
controlled = [row for item in shop_pages for row in item["shops"]
              if str(row.get("code") or "").startswith("MKT100V1-SHOP-")]
for mode, rows in (("ONLINE_PURCHASE", buy0), ("ONLINE_PURCHASE", buy1),
                   ("VISIT_TO_BUY", visit0), ("VISIT_TO_BUY", visit1),
                   ("SERVICE_AT_SHOP", service0), ("SERVICE_AT_SHOP", service1)):
    assert isinstance(rows, list)
    assert len(rows) <= 12
    assert all(item.get("commerceMode") == mode for item in rows)
if operation == "SEED":
    assert len(controlled) == 100, {"controlled": len(controlled), "pages": shop_pages}
    assert len({row["shopId"] for row in controlled}) == 100, controlled
    assert len({row["displayName"] for row in controlled}) == 100, controlled
    assert len(buy0) and len(visit0) and len(service0), {
        "buy_online": len(buy0), "visit_to_buy": len(visit0), "service_at_shop": len(service0)}
    assert len({x.get("shopId") for x in buy0}) > 1, buy0
    assert not all(x.get("shopId") == 1 for x in buy0), "Shop #1 fallback monopolised the marketplace feed"
    keys0 = {(x.get("productId"), x.get("commerceMode"), x.get("shopId"), x.get("productVariantId")) for x in buy0}
    keys1 = {(x.get("productId"), x.get("commerceMode"), x.get("shopId"), x.get("productVariantId")) for x in buy1}
    assert not keys0.intersection(keys1), "marketplace feed pages overlap"
print(json.dumps({"shop_page_rows": [len(item["shops"]) for item in shop_pages],
                  "shop_pages_traversed": len(shop_pages),
                  "shop_total_elements": shop_pages[0]["totalElements"],
                  "controlled_shop_ids_returned": len({row["shopId"] for row in controlled}),
                  "shop_has_next": shop_pages[0]["hasNext"],
                  "buy_online_page_rows": [len(buy0), len(buy1)],
                  "visit_to_buy_page_rows": [len(visit0), len(visit1)],
                  "service_at_shop_page_rows": [len(service0), len(service1)]}, sort_keys=True))
PY
