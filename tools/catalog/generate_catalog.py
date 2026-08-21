#!/usr/bin/env python3
"""
Expands catalog_spec.CATALOG into the seed file the backend reads.

RUN:  python3 tools/catalog/generate_catalog.py
OUT:  backend/src/main/resources/catalog/gp-store-test-catalog.json

DETERMINISTIC BY CONSTRUCTION. The random number generator is seeded from
each product's own SKU, not from a global sequence, so re-running produces
byte-identical output and - more importantly - INSERTING a product does not
reshuffle the stock levels and discounts of every product after it. A
generator whose output churns on every edit cannot be reviewed in a diff.

EVERY PRICE HERE IS ASSUMED. Nothing was read from a shelf, a website or an
API. Rates come from catalog_spec's per-product base rate, which is a
plausible Indian retail rate per kg/litre/piece. Everything lands in the
database with is_test_data = true and price_verified = false.
"""
import hashlib
import json
import os
import random
import re

from catalog_spec import CATALOG

OUT = os.path.join(os.path.dirname(__file__), "..", "..",
                   "backend", "src", "main", "resources", "catalog",
                   "gp-store-test-catalog.json")

# Extra pack sizes that genuinely exist for these subcategories. Applied only
# where the spec did not already list them, and only to reach a catalogue of
# realistic depth - a shop that stocks atta stocks it in more than one bag.
# Deliberately absent for anything sold in one size (agarbatti, notebooks) and
# for expensive spices, where a 5 kg pack would be nonsense.
PACK_LADDER = {
    "Atta":            [(2, "kg")],
    "Rice":            [(2, "kg")],
    "Pulses":          [(2, "kg")],
    "Cooking Oil":     [(500, "ml"), (2, "l")],
    "Ghee":            [(200, "ml")],
    "Detergent Powder":[(4, "kg")],
    "Dishwash":        [(125, "g")],
    "Tea":             [(100, "g")],
    "Milk Drinks":     [(200, "g")],
    "Namkeen":         [(50, "g")],
    "Chips & Crisps":  [(25, "g")],
    "Bathing Soap":    [(50, "g")],
    "Shampoo":         [(80, "ml")],
    "Oral Care":       [(50, "g")],
    "Breakfast Cereal":[(250, "g")],
    "Ketchup & Sauces":[(200, "g")],
    "Packaged Water":  [(250, "ml")],
    "Sugar":           [(2, "kg")],
    "Salt":            [(500, "g")],
}

BASE_UNIT = {"g": ("kg", 1000.0), "kg": ("kg", 1.0),
             "ml": ("l", 1000.0), "l": ("l", 1.0),
             "pcs": ("pcs", 1.0)}

# The discount ladder asked for. Weighted so most products sit at a modest
# discount and full-price and deep-cut items are both a minority - which is
# what a real grocery shelf looks like. A catalogue where everything is
# exactly 10% off reads as generated, because it is.
DISCOUNTS = [0, 0, 0, 3, 3, 5, 5, 5, 8, 8, 10, 10, 10, 12, 12, 15, 15, 20]

# Stock levels. Weighted towards "available", with a small tail of low and
# zero stock so the out-of-stock UI is actually exercised during testing.
STOCK_LEVELS = [0, 3, 6, 10, 15, 24, 24, 32, 32, 50, 50, 75, 100, 100, 150]

# Products a kirana genuinely sells most of. Used for the bestseller flag so
# it lands on staples rather than at random.
BESTSELLER_STEMS = [
    "Salt Iodised", "Shudh Chakki Atta", "Sunlite Refined Sunflower Oil",
    "Taaza Toned Milk", "Gold Full Cream Milk", "Butter Pasteurised",
    "Parle-G Original Gluco Biscuits", "Marie Gold Biscuits",
    "2-Minute Masala Noodles", "Classic Salted Potato Chips",
    "India's Magic Masala Chips", "Tea Gold", "Tea Premium",
    "Red Label Tea", "Basmati Rice Classic", "Toor Dal Unpolished",
    "Pure Sugar", "Soft Drink", "Lime Flavoured Soft Drink",
    "Dairy Milk Chocolate", "Pure Ghee", "Masti Dahi", "Aloo Bhujia",
    "Easy Wash Detergent Powder", "Dishwash Bar", "Dishwash Liquid Gel",
    "Strong Teeth Toothpaste", "Total 10 Soap", "Packaged Drinking Water",
    "Fresh Tomato Ketchup", "Turmeric Powder", "Red Chilli Powder",
    "Garam Masala", "Chakki Fresh Atta", "Malai Paneer", "Corn Flakes Original",
]

STOPWORDS = {"the", "and", "with", "of", "for", "in", "a"}


def rng_for(sku):
    """Per-SKU RNG - see the module docstring on why this is not global."""
    seed = int(hashlib.sha256(sku.encode()).hexdigest()[:12], 16)
    return random.Random(seed)


def pack_label(qty, unit):
    q = int(qty) if float(qty) == int(qty) else qty
    return f"{q} {unit}"


def base_quantity(qty, unit):
    """Pack size expressed in the base unit its rate is quoted in."""
    _, divisor = BASE_UNIT[unit]
    return float(qty) / divisor


def round_price(value):
    """
    Indian retail prices cluster on whole rupees, and on ...5 and ...9 at the
    lower end. Rounding to the nearest rupee alone produces prices like 187
    and 243 across a whole catalogue, which reads wrong even though no single
    one does.
    """
    if value < 100:
        return float(int(round(value)))
    if value < 1000:
        return float(int(round(value / 5.0) * 5))
    return float(int(round(value / 10.0) * 10))


def describe(brand, stem, subcategory, pack):
    """
    A plain sentence about what the thing is. No health claims, no
    certifications, no superlatives - see the brief's section 9. The
    strongest word permitted here is "everyday".
    """
    templates = {
        "Atta": "{brand} {stem} in a {pack} pack, for everyday rotis, parathas and Indian breads.",
        "Rice": "{brand} {stem}, {pack} pack. Suitable for everyday meals, pulao and biryani.",
        "Pulses": "{brand} {stem} in a {pack} pack, for everyday dal and Indian home cooking.",
        "Cooking Oil": "{brand} {stem}, {pack}. For everyday Indian cooking, frying and tempering.",
        "Ghee": "{brand} {stem} in a {pack} pack, for tempering, sweets and everyday cooking.",
        "Ground Spices": "{brand} {stem}, {pack} pack. Adds colour and flavour to everyday Indian dishes.",
        "Blended Masala": "{brand} {stem} in a {pack} pack, a ready spice blend for Indian home cooking.",
        "Whole Spices": "{brand} {stem}, {pack}. Whole spice for tempering and slow-cooked dishes.",
        "Tea": "{brand} {stem}, {pack} pack. For everyday strong Indian chai.",
        "Coffee": "{brand} {stem} in a {pack} pack, for quick everyday coffee.",
        "Milk": "{brand} {stem}, {pack} pack. For everyday tea, coffee and household use.",
        "Bathing Soap": "{brand} {stem}, {pack} pack, for everyday bathing.",
        "Shampoo": "{brand} {stem} in a {pack} pack, for regular hair washing.",
        "Detergent Powder": "{brand} {stem}, {pack} pack, for everyday laundry.",
        "Dishwash": "{brand} {stem}, {pack}. For everyday kitchen dishwashing.",
        "Packaged Water": "{brand} {stem}, {pack} pack.",
    }
    t = templates.get(subcategory,
                      "{brand} {stem} in a {pack} pack, a regular {sub} item for the Indian kitchen.")
    return t.format(brand=brand, stem=stem, pack=pack, sub=subcategory.lower())


def keywords_for(brand, stem, category, subcategory, qty, unit):
    """
    What a customer might actually type. Includes the brand, every meaningful
    word of the name, the subcategory, and BOTH spellings of the pack size -
    someone searching "1 litre" and someone searching "1L" are the same
    person.
    """
    words = []
    for token in re.split(r"[^A-Za-z0-9]+", f"{brand} {stem} {subcategory}"):
        t = token.lower()
        if t and t not in STOPWORDS and not t.isdigit():
            words.append(t)

    q = int(qty) if float(qty) == int(qty) else qty
    words.append(f"{q}{unit}".lower())
    words.append(f"{q} {unit}".lower())
    if unit == "l":
        words += [f"{q} litre", f"{q}l"]
    if unit == "ml":
        words.append(f"{q} ml")
    if unit == "kg":
        words += [f"{q} kilo", f"{q} kg"]

    seen, out = set(), []
    for w in words:
        if w not in seen:
            seen.add(w)
            out.append(w)
    return out[:22]


def expand():
    rows, seen = [], set()
    for category, subcategory, brand, stem, packs, rate in CATALOG:
        allpacks = list(packs)
        for extra in PACK_LADDER.get(subcategory, []):
            if extra not in allpacks:
                allpacks.append(extra)
        for qty, unit in allpacks:
            key = (brand, stem, qty, unit)
            if key in seen:      # duplicate protection at the source
                continue
            seen.add(key)
            rows.append((category, subcategory, brand, stem, qty, unit, rate))
    return rows


def build():
    rows = expand()
    products = []
    for index, (category, subcategory, brand, stem, qty, unit, rate) in enumerate(rows, start=1):
        sku = f"GP-{index:06d}"
        rnd = rng_for(sku)

        pack = pack_label(qty, unit)
        name = f"{brand} {stem} {pack}"

        mrp = round_price(base_quantity(qty, unit) * rate)
        if mrp < 5:
            mrp = 5.0
        discount = rnd.choice(DISCOUNTS)
        selling = round_price(mrp * (100 - discount) / 100.0)
        if selling > mrp:            # rounding can only ever push it up
            selling = mrp
        actual_discount = 0 if mrp == 0 else round((mrp - selling) / mrp * 100)

        stock = rnd.choice(STOCK_LEVELS)
        bestseller = any(s in stem for s in BESTSELLER_STEMS) and rnd.random() < 0.55
        featured = (not bestseller) and rnd.random() < 0.07

        products.append({
            "sku": sku,
            "name": name,
            "brand": brand,
            "category": category,
            "subcategory": subcategory,
            "packQuantity": float(qty),
            "packUnit": unit,
            "mrp": mrp,
            "sellingPrice": selling,
            "discountPercent": actual_discount,
            "description": describe(brand, stem, subcategory, pack),
            "stock": stock,
            "available": stock > 0,
            "active": True,
            "bestseller": bestseller,
            "featured": featured,
            "searchKeywords": keywords_for(brand, stem, category, subcategory, qty, unit),
            # Left empty ON PURPOSE. No image URL is invented here - the
            # backfill job fills these from Open Food Facts, and only with
            # URLs it has confirmed resolve. See CatalogImageBackfillService.
            "images": [],
            "isTestData": True,
            "priceVerified": False,
            "dataSource": "generated-test-catalog",
            "imageSource": None,
        })
    return products


def main():
    products = build()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump({"version": 1,
                   "generator": "tools/catalog/generate_catalog.py",
                   "note": "TEST DATA. Assumed prices, never verified against any shelf or site.",
                   "products": products}, fh, ensure_ascii=False, indent=1)

    cats = {p["category"] for p in products}
    subs = {(p["category"], p["subcategory"]) for p in products}
    brands = {p["brand"] for p in products}
    best = sum(1 for p in products if p["bestseller"])
    feat = sum(1 for p in products if p["featured"])
    oos = sum(1 for p in products if p["stock"] == 0)
    print(f"products      : {len(products)}")
    print(f"categories    : {len(cats)}")
    print(f"subcategories : {len(subs)}")
    print(f"brands        : {len(brands)}")
    print(f"bestsellers   : {best}")
    print(f"featured      : {feat}")
    print(f"out of stock  : {oos}")
    print(f"written       : {os.path.relpath(OUT)}")


if __name__ == "__main__":
    main()
