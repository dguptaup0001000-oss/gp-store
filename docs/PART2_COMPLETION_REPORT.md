# GP-STORE — Part 2 completion report

*Part 2 §21. Written after Parts 1, 3 and 4 had already landed, so the first
job was an audit: how much of Part 2 was already standing, and what was
genuinely missing.*

---

## What was already there, and was left alone

Part 2 arrived last, and a good deal of it had already been built by the
foundation work. **None of it was rebuilt.**

| §  | Requirement | Where it already lived |
|----|-------------|------------------------|
| §9 | Central catalog + shop-specific data | `Product`/`ProductVariant` central; `ShopProductVariant` per shop |
| §11| Merchant product management | `ShopCatalog.list/delist`, `/api/shop/listings/**` |
| §14| Shop switching without destroying other baskets | `CartItem.shopId`, cart survives a switch |
| §16| Shop hours in the customer UI | `StorefrontView.openNow/acceptingOrders/pausedUntil/closedToday` |
| §17| Merchant pause controls | `POST /api/admin/store/pause` — 30 min, until, rest of day |
| §18| Checkout split into one order per shop | `OrderGroupService` |
| §19| Per-shop order number, bill, payment, refund | Already per shop |
| §10| Keep visible / show out of stock / disable Add | `inStock` + `isBuyable` on the card |

## What was missing, and is now built

### §4 — preferred shops, per category

`customer_preferred_shops` (V62), `PreferredShops`, `/api/preferred-shops`.

Up to two per category — and "up to two" is enforced by the *shape* of the
table rather than by a count. Each row occupies slot 1 or slot 2 under a
unique index on `(customer, category, slot)`, so a third preference has
nowhere to go. A service-layer `count() >= 2` check would be two concurrent
requests away from being three.

The rule that took the most care is §4's last line — *do not silently override
the customer's explicit preference simply because another shop is cheaper*.
`preferredFirst` **reorders and returns every shop it was given**. There is no
filter anywhere that consults a preference. Three tests hold that in place.

### §5 — Best Deal, which cannot be bought

`BestDeal` sorts lexicographically over questions a customer would recognise,
in the order they would ask them: can I actually buy it → is the delivery
charge known → what is the final cost → which is nearer → which is more
reliable. No weighted score: weights are invented numbers, and an invented
number that decides whose shop appears first is exactly what gets adjusted
later for reasons nobody writes down.

*Do NOT rank merchants merely because they pay GP-STORE more money* is kept by
an **absence**. `BestDeal` ranks `ShopOffer`s, and a `ShopOffer` carries no
commission, tier, ledger or billing component — there is no route by which
what a merchant pays could reach the ranking. A reflection test fails if such
a field is ever added.

### §6 — the radius ladder is configuration

Was `3, 5, 10, 15, 25` as a `static final`. Now
`marketplace.search.radii-km`, defaulting to exactly that list so no running
deployment changes. Both of the brief's examples work — `8,20,50,100,500` for
a town, `3,8,15,30,100` for a city.

A search that finds nothing at the rung asked for **climbs until it does and
says so**, in the server's own words: *"No shops within 8 km. Showing shops
within 20 km."* A client rebuilding that from two numbers eventually rebuilds
it as "no shops nearby" when there are twelve, two rungs out.

A malformed ladder falls back whole rather than failing boot — a typo in one
environment variable taking the marketplace offline is a worse failure than
running on the default rungs.

### §7 / §8 — final cost, and the 25% rule measured on it

`ShopOffers` builds, per shop and inside that shop's own scope: the shop's
price, its discount, and **its own delivery charge for this customer's
distance**. `finalPayable` is assembled there and nowhere else, because two
screens adding up three numbers would eventually add them up differently.

`PriceGapRule.qualifies(localFinal, fartherFinal)` — parameters named for
finals, because on the *product price* the brief's own example is a dead heat
at exactly 1.25 and on the final cost it is 1.33. The dangerous direction is
the other one: a cheap product behind an expensive delivery looks like a
bargain on the product line and is not one. There is a test for exactly that
reversal.

`deliveryChargeKnown` exists because **zero is a lie**. A shop that cannot
quote has not offered free delivery, and treating it as zero would put it top
of a Best Deal list on a number nobody gave.

### §10 — the price is now actually hidden

"Keep visible, show Out of stock, disable Add to Cart, **hide price**" — the
first three were already true and the fourth was not. `VariantResponse` now
withholds price and MRP when stock is known to be zero, **in the response**
rather than in a widget: there are three clients and a public API, and a rule
enforced in one Dart file is a rule the other two do not have. Null stock (an
admin catalogue screen, a platform report) keeps its price.

### §12 — there is no price floor, and now a test says so

A scan of the whole main source tree for `PRICE_FLOOR`, `MIN_SELLING_PRICE`
and neighbours, plus a case asserting a shop selling at ₹12 against a
catalogue price of ₹500 is charged ₹12.

### §13 — the basket drawn as the several purchases it is

`CartByShop`, `GET /api/carts/mine/by-shop`. Per-shop subtotal, delivery,
total. The combined figure is named `informationalCombinedTotal`, with
`isSinglePayment` stated beside it — a field called `total` on a cart response
is an invitation to draw it large and put a Pay button under it.

### §15 — a preferred shop that does not have it

`GET /api/discovery/elsewhere` returns whether the preferred shop has it and
who else does, with **neither option preselected**. §15 leaves the choice with
the customer, and a default would be the system making it.

### §2 — the shop profile

`GET /api/marketplace/shops/{id}` now also carries the rating summary:
lifetime average, the 90-day average, and the verified count.

---

## Database changes

| Migration | What |
|---|---|
| `V62__preferred_shops_per_category.sql` | `customer_preferred_shops` — two slots per customer per category, enforced by a unique index and a CHECK. Fails if the migration chose anybody a shop. |

No existing table was altered.

## APIs added

| Method | Path | Who |
|---|---|---|
| GET / PUT / DELETE | `/api/preferred-shops`, `/api/preferred-shops/{categoryId}` | customer |
| GET | `/api/discovery/compare` | customer |
| GET | `/api/discovery/preferred` | customer |
| GET | `/api/discovery/elsewhere` | customer |
| GET | `/api/carts/mine/by-shop` | customer |

## APIs changed

| Path | Change | Compatibility |
|---|---|---|
| `/api/marketplace/discovery` | Gained `askedRadiusKm`, `widened`, `message`; with a `radiusKm` it now widens progressively instead of answering an empty rung | Additive. With **no** `radiusKm` the behaviour is byte-for-byte what it was, which is the call a released app makes |
| `/api/marketplace/shops/{id}` | Gained `rating` | Additive |
| Any response carrying `VariantResponse` | `sellingPrice` / `mrp` are now `null` on a listed item with zero stock | **Behaviour change**, required by §10. A client that drew the price without checking `inStock` will now draw nothing there |

## UI changes

**None.** This slice is backend only. See *Known limitations*.

## Tests

| Suite | Tests | Result |
|---|---|---|
| `FinalCostDecidesTest` | 19 | pass |
| `TwoWaysToFindAShopTest` (Shops A, B, C) | 17 | pass |
| Full backend suite | see below | — |

Covered: preferred shops (per category, two-slot limit, ordering not
filtering, cross-customer isolation, non-browsable shops refused), Best Deal
(final cost, availability first, unknown delivery, no pay-for-ranking), the
25% rule on final cost including the reversal case, the configurable ladder
and its fallbacks, progressive widening and its message, out-of-stock price
hiding, the absence of a price floor, and the basket's per-shop shape.

## Known limitations

1. **No Flutter work in this slice.** §1, §8's comparison screen, §13's cart
   sectioning and §15's "find it elsewhere" prompt are backend-complete and
   have no UI. The endpoints exist and are tested; nothing draws them yet.
2. **Flutter tests were not run**, and the real two-shop app journey was not
   re-driven. The `VariantResponse` price change in particular touches a
   contract the app parses — **re-run that journey before release**.
3. **Delivery charge in a comparison is quoted for the single item.** A
   customer comparing one variant gets that shop's charge for that item; a
   basket of six may be quoted differently. The figure is honest for the
   comparison it is on, and `/api/carts/mine/by-shop` is the basket-level
   answer.
4. **§8's "applicable customer charges" and "discounts"** are reported as the
   shop's MRP-vs-price difference. Shop-specific offer campaigns (§11's
   "create shop-specific offers") do not exist as a separate construct, so
   there is no offer engine feeding the comparison.
5. **§3's two modes are endpoints, not a stored setting.** There is no "this
   customer prefers Best Deal" flag; the app picks the route. That is a
   deliberate omission — a stored mode would be a fourth place the customer's
   intent lives.

## Remaining work

Part 3 and Part 4 landed before this. What is still open across all four
parts is listed in `docs/ARCHITECTURE_REPORT.md` §24, which is the single
place to read before deciding anything about release. The headline items are
unchanged: the payment model (one account for many merchants), the commercial
amounts, the Android build blocker, and the un-re-run Flutter journey.
