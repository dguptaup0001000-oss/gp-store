// Snapshot known-good catalog rows for the load test.
//
// The public category list is capped at 100 names. Local DBs that ran
// catalog tests can crowd that list with leftover "Bestseller Correct …"
// rows, so picking a random category ID from GET /api/categories creates
// artificial empty pages and checkout 404s. This script records:
//   - real shop categories (Atta/Rice/Dal, oil, …)
//   - products whose variants have an inventory row with stock > reserved
//   - deterministic search terms
//
// It never invents IDs. If the DB has no shoppable inventory, it exits 1.
//
// Usage:
//   BASE_URL=http://localhost:8081/v1 node snapshot-fixtures.js

import fs from 'node:fs';
import { execFileSync } from 'node:child_process';

const BASE_URL = (process.env.BASE_URL || 'http://localhost:8081/v1').replace(/\/$/, '');
const OUT_FILE = process.env.OUT_FILE || new URL('./fixtures.json', import.meta.url);
const SEARCH_TERMS = ['rice', 'oil', 'atta', 'dal', 'soap', 'milk', 'tea', 'salt', 'sugar', 'biscuit'];
const SHOP_CATEGORY = /atta|rice|dal|oil|soap|milk|tea|biscuit|salt|sugar|masala|spice|snacks|beverages/i;
const LEFTOVER_NAME = /^(perf category|bestseller correct|featured correct|test product)/i;

function isLeftoverName(name) {
  return LEFTOVER_NAME.test(String(name || '').trim());
}

function dbRows() {
  const sql = `
    SELECT p.id AS product_id,
           p.name AS product_name,
           p.category_id,
           c.name AS category_name,
           pv.id AS variant_id,
           COALESCE(i.stock, 0) - COALESCE(i.reserved_stock, 0) AS available_qty
    FROM products p
    JOIN categories c ON c.id = p.category_id
    JOIN product_variants pv ON pv.product_id = p.id
    JOIN inventory i ON i.product_variant_id = pv.id
    WHERE p.active = true
      AND pv.available = true
      AND COALESCE(i.stock, 0) - COALESCE(i.reserved_stock, 0) > 0
      AND c.name NOT ILIKE 'Perf Category%'
      AND c.name NOT ILIKE 'Bestseller Correct%'
      AND p.name NOT ILIKE 'Perf %'
      AND p.name NOT ILIKE 'Bestseller Correct%'
      AND (
        p.name ILIKE '%rice%' OR p.name ILIKE '%oil%' OR p.name ILIKE '%atta%'
        OR p.name ILIKE '%dal%' OR c.name ILIKE '%rice%' OR c.name ILIKE '%atta%'
        OR c.name ILIKE '%oil%' OR c.name ILIKE '%dal%'
      )
    ORDER BY available_qty DESC, p.id
    LIMIT 40;
  `;
  try {
    const out = execFileSync(
      'psql',
      ['-h', process.env.PGHOST || 'localhost', '-U', process.env.PGUSER || 'gpstore',
       '-d', process.env.PGDATABASE || 'gpstore_test', '-At', '-F', '\t', '-c', sql],
      {
        env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'gpstore_test_password' },
        encoding: 'utf8',
      },
    );
    return out
      .trim()
      .split('\n')
      .filter(Boolean)
      .map((line) => {
        const [productId, productName, categoryId, categoryName, variantId, availableQty] = line.split('\t');
        return {
          productId: Number(productId),
          productName,
          categoryId: Number(categoryId),
          categoryName,
          variantId: Number(variantId),
          availableQty: Number(availableQty),
        };
      })
      .filter((row) => row.productId && row.variantId);
  } catch (e) {
    console.warn('psql snapshot skipped:', e.message);
    return [];
  }
}

async function getJson(path) {
  const res = await fetch(`${BASE_URL}${path}`);
  if (!res.ok) {
    throw new Error(`${path} -> HTTP ${res.status}`);
  }
  return res.json();
}

async function main() {
  const health = await fetch(`${BASE_URL}/api/health`);
  if (!health.ok) {
    throw new Error(`health ${health.status} - is the backend running at ${BASE_URL}?`);
  }

  const categories = await getJson('/api/categories');
  if (!Array.isArray(categories) || categories.length === 0) {
    throw new Error('GET /api/categories returned no array');
  }
  const shopCategories = categories.filter((c) => SHOP_CATEGORY.test(c.name || '') && !isLeftoverName(c.name));

  const db = dbRows();
  const products = [];
  const seen = new Set();
  for (const row of db) {
    if (!row.productId || !row.variantId || Number.isNaN(row.variantId)) continue;
    if (seen.has(row.productId)) continue;
    seen.add(row.productId);
    products.push({
      id: row.productId,
      name: row.productName,
      categoryId: row.categoryId,
      categoryName: row.categoryName,
      variantId: row.variantId,
      availableQty: row.availableQty,
    });
  }

  // API fallback if psql is unavailable: search known terms and keep cards
  // that advertise an available variant. Inventory is still verified when
  // DB rows exist; API-only fixtures are tagged so checkout can prefer DB ones.
  if (products.length === 0) {
    for (const term of ['rice', 'oil', 'atta']) {
      const page = await getJson(`/api/products/search/instant?keyword=${encodeURIComponent(term)}&page=0&size=20`);
      for (const product of page.content || []) {
        const categoryName = product.category && product.category.name;
        if (isLeftoverName(product.name) || isLeftoverName(categoryName)) continue;
        const variant = (product.variants || []).find((v) => v.available);
        if (!variant) continue;
        products.push({
          id: product.id,
          name: product.name,
          categoryId: product.category && product.category.id,
          categoryName: product.category && product.category.name,
          variantId: variant.id,
          availableQty: null,
          source: 'api',
        });
      }
    }
  }

  if (products.length === 0) {
    throw new Error('No shoppable products with inventory found. Refusing to invent IDs.');
  }

  const fixtures = {
    generatedAt: new Date().toISOString(),
    baseUrl: BASE_URL,
    searchTerms: SEARCH_TERMS,
    categories: (shopCategories.length ? shopCategories : categories.slice(0, 20)).map((c) => ({
      id: c.id,
      name: c.name,
    })),
    allCategoryCount: categories.length,
    products: products.slice(0, 30),
    shoppableVariantIds: [...new Set(products.map((p) => p.variantId))],
    notes: db.length
      ? 'Variants confirmed against inventory.stock - reserved_stock > 0. Leftover Perf/Bestseller Correct rows excluded. Demo kirana rows may have is_test_data=true in this database.'
      : 'API-only snapshot; inventory rows were not verified via psql.',
  };

  fs.writeFileSync(OUT_FILE, JSON.stringify(fixtures, null, 2));
  console.log(
    `Wrote ${fixtures.products.length} products, ${fixtures.categories.length} categories, ` +
      `${fixtures.shoppableVariantIds.length} variants to ${OUT_FILE}`,
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
