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
[[ "$OPERATION" == "SEED" || "$OPERATION" == "CLEANUP" ]] || die "Operation must be SEED or CLEANUP."
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
compose run --rm --no-deps \
  -e "GPSTORE_TEST_DATA_CONFIRMATION=$BATCH_ID" \
  -e "GPSTORE_TEST_DATA_ALLOW_PRODUCTION=true" \
  -e "GPSTORE_TEST_DATA_EXPECTED_SHA=$TARGET_SHA" \
  backend \
  --spring.main.web-application-type=none \
  --spring.profiles.active=prod,marketplace-test-data-cli \
  --gpstore.synthetic-data.action="$OPERATION" \
  --gpstore.synthetic-data.batch="$BATCH_ID" \
  --gpstore.synthetic-data.expected-commit="$TARGET_SHA"

if [[ "$OPERATION" == "SEED" ]]; then
  read -r -d '' verification_sql <<'SQL' || true
SELECT json_build_object(
      'merchants', (SELECT count(*) FROM merchants WHERE is_demo = TRUE AND left(legal_name, length('[MARKETPLACE_TEST_100_SHOPS_V1] Merchant ')) = '[MARKETPLACE_TEST_100_SHOPS_V1] Merchant '),
      'shops', (SELECT count(*) FROM shops WHERE is_demo = TRUE AND code ~ '^MKT100V1-SHOP-[0-9]{3}$'),
      'products', (SELECT count(*) FROM products WHERE is_test_data = TRUE AND data_source = 'MARKETPLACE_TEST_100_SHOPS_V1'),
      'variants', (SELECT count(*) FROM product_variants v JOIN products p ON p.id = v.product_id WHERE p.is_test_data = TRUE AND p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1'),
      'listings', (SELECT count(*) FROM shop_product_variants spv JOIN product_variants v ON v.id = spv.product_variant_id JOIN products p ON p.id = v.product_id WHERE p.is_test_data = TRUE AND p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1'),
      'buy_online', (SELECT count(*) FROM shop_product_variants spv JOIN product_variants v ON v.id = spv.product_variant_id JOIN products p ON p.id = v.product_id WHERE p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' AND spv.commerce_mode = 'ONLINE_PURCHASE'),
      'visit_to_buy', (SELECT count(*) FROM shop_product_variants spv JOIN product_variants v ON v.id = spv.product_variant_id JOIN products p ON p.id = v.product_id WHERE p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' AND spv.commerce_mode = 'VISIT_TO_BUY'),
      'service_at_shop', (SELECT count(*) FROM shop_product_variants spv JOIN product_variants v ON v.id = spv.product_variant_id JOIN products p ON p.id = v.product_id WHERE p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' AND spv.commerce_mode = 'SERVICE_AT_SHOP'),
      'image_products', (SELECT count(DISTINCT p.id) FROM products p JOIN product_variants v ON v.product_id = p.id LEFT JOIN product_images pi ON pi.product_id = p.id WHERE p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' AND (NULLIF(v.image_url, '') IS NOT NULL OR pi.id IS NOT NULL)),
      'undersized_shops', (SELECT count(*) FROM (SELECT s.id FROM shops s LEFT JOIN shop_product_variants spv ON spv.shop_id = s.id LEFT JOIN product_variants v ON v.id = spv.product_variant_id LEFT JOIN products p ON p.id = v.product_id AND p.is_test_data = TRUE AND p.data_source = 'MARKETPLACE_TEST_100_SHOPS_V1' WHERE s.is_demo = TRUE AND s.code ~ '^MKT100V1-SHOP-[0-9]{3}$' GROUP BY s.id HAVING count(p.id) < 50) small)
    )::text;
SQL
  metrics="$(compose exec -T postgres sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "$1"' sh "$verification_sql")"
  python3 - "$metrics" <<'PY'
import json, sys
m = json.loads(sys.argv[1])
assert m["merchants"] == 100, m
assert m["shops"] == 100, m
assert m["products"] >= 5000 and m["variants"] >= 5000 and m["listings"] >= 5000, m
assert m["buy_online"] > 0 and m["visit_to_buy"] > 0 and m["service_at_shop"] > 0, m
assert m["undersized_shops"] == 0, m
assert m["image_products"] == 0, m
print(json.dumps(m, sort_keys=True))
PY
else
  echo "Exact synthetic batch cleanup completed."
fi
