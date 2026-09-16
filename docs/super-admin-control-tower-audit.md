# Super Admin control-tower implementation audit

Audited baseline: production commit `063b1cfb43c1e34b14425833319c0ea14f5f636d`.

This document records the implementation that existed before the control-tower work. It is the design boundary for the work: existing services and security rules are extended, not replaced.

## Existing architecture to reuse

| Area | Existing implementation | Decision |
| --- | --- | --- |
| Authentication | `SecurityConfig`, `JwtFilter`, `AuthenticatedUser`, `RolePermissions` | Keep one authentication and permission system. Every `/api/platform/**` route remains protected by `PERM_PLATFORM_ADMIN`. |
| Tenant isolation | `TenantContextFilter`, `ShopScopeFilter`, `TenantEntityListener`, live staff membership checks | Keep backend shop scope authoritative. Platform reads use explicit safe projections; merchant writes remain tenant-filtered. |
| Merchant lifecycle | `PlatformMerchantController`, `MerchantLifecycleService`, governance services | Reuse approve/review/trade/pause/suspend/reactivate/onboarding operations and their state machines. |
| Merchant/shop model | `Merchant`, `Shop`, `ShopStaff`, `MerchantStatus`, `ShopStatus` | No parallel merchant or shop identity model. |
| Customer administration | paged customer list and `AdminCustomerDetailService` | Reuse bounded detail data but add platform-safe masked projections and audited reveal. |
| Orders | paged order queries, `AdminOrderService`, explicit order DTOs | Reuse authoritative order data. Add platform filters and derive timelines only from real audit/history events. |
| Payments/refunds | `PaymentResponse`, provider event verification, `Refund`, locked/idempotent refund flows | Reuse. Never expose provider secrets; do not add a second refund path. |
| Returns | `OrderReturn` and transactional approval/refund/stock restoration | Reuse as the returns control-center source. |
| Reviews | verified-purchase reviews, report/moderation, merchant/customer replies | Reuse. Product and shop/service ratings remain distinct. |
| Workers | `WorkerAdminController/Service`, shop-owned worker model | Reuse; add platform context and pagination. Location is shown only when an active operational assignment and fresh location justify it. |
| Products/inventory | shared catalogue plus `ShopProductVariant` and shop-owned inventory | Reuse. Shop price/availability comes from shop rows, never from a fabricated global price. Cost price is excluded from platform APIs unless a later policy explicitly authorizes it. |
| Finance | `ShopEarnings`, `MerchantSales`, weekly billing and append-only merchant ledger | Reuse `BigDecimal` and authoritative server-side aggregates. GMV, merchant sales, delivery charges, commission, platform fees, refunds and adjustments stay separate. |
| Audit | `AuditLog`, `AuditLogService`, paged audit screen | Extend the existing log with structured context and database append-only protection. Do not create a parallel audit system. |
| Security/operations | request IDs, rate limits, safe ops status, actuator authorization, `/api/version` | Reuse. Platform search joins the search limiter; system health exposes no infrastructure secret. |
| Presence | Redis sorted-set rolling five-minute authenticated-account presence | It may be labelled only as “authenticated accounts active in the last five minutes,” never “live customers/workers.” |
| Flutter design | shared GP-STORE tokens/components, `AdminShell`, platform console | Reuse visual components and repositories. Give Super Admin its own destination set without copying the merchant application. |

## Current behavior that is already correct

- Super Admin is a separate application entry point and requires the platform permission.
- Super Admin no longer renders merchant shop switching.
- A merchant with one shop has no switch control; a merchant with two or more shops does.
- Selecting a shop is still verified against live backend membership.
- Merchant activation uses email, temporary password and an exact 15-character one-time activation code, then forces a permanent-password change.
- Merchant approval and customer-facing trading are separate lifecycle decisions.
- Delivery charges are excluded from the platform commission base.
- Payments/refunds use decimal values, row locks and idempotency constraints.
- Review removal is moderation with a reason/audit trail, not merchant deletion.
- Commerce history uses lifecycle/soft-delete semantics rather than destructive deletion.
- NDK `28.2.13676358`, CameraX `1.4.2`, and final-artifact ELF/ZIP 16-KB validation are active.

## Gaps found

| Requirement | Gap in baseline | Planned extension |
| --- | --- | --- |
| Control-tower home | Existing dashboard is shop-scoped; platform overview is fixed to 30 days and has ambiguous “gross sales” wording. | Server-side range aggregates and compact, explicitly defined KPIs/drill-downs. |
| Global search | Only catalogue search exists. Some admin Flutter screens repeatedly download pages and search locally. | Platform-only, paged, parameterized multi-entity search with safe previews and rate limiting. |
| Customer 360 | Shop-oriented detail; PII is plaintext; no audited reveal. `Customer` also exposes activation hash fields if serialized raw. | Safe DTO, masked PII by default, reasoned reveal, explicit secret-exclusion tests, future-accurate nullable creation date. |
| Merchant/shop 360 | Existing detail is merchant plus shops, with limited performance/finance/history. | Bounded platform projections and linked drill-downs, reusing lifecycle/governance. |
| Order control center | Shop order detail exists; no complete platform filter set or durable status-event table. | Paged platform queries. Timeline uses only existing audit/history facts and marks unavailable history honestly. |
| Finance center | Authoritative building blocks exist but no complete platform read model. | Server-side `BigDecimal` aggregates with definitions and filters. Never call GMV platform revenue. |
| Payments/refunds | Shop screens exist; no platform-wide observability view. Refund UI does not consistently require a reason. | Safe paged projections. High-risk actions retain authorization, confirmation, reason and existing idempotency. |
| Security center | Events are spread across audit/application logs; some auth outcomes are not audit events. | Add safe structured security audit events only where a real event exists. No invented metrics. |
| Audit integrity | Free-text details, incomplete target context, no request ID column, best-effort swallowing, no DB append-only trigger. | Extend this table/service with structured fields and append-only DB trigger while preserving existing rows/callers. |
| Pagination | Platform merchant/shop and worker lists are unpaginated. | Bounded Spring `Page` endpoints and lazy Flutter loading. |
| Search indexes | Catalogue trigram and commerce indexes exist; platform identity lookup indexes are incomplete. | Add only query-backed lower/trigram/composite indexes, with migration tests. |
| Complaints | Governance appeals, returns, refund disputes and review reports exist; no general customer support-ticket domain exists. | Present existing real sources. Do not fabricate a generic complaints count or duplicate support model. |
| Release matrix | Customer/Admin/Worker AAB and all four APK variants are built. Super Admin AAB is missing. | Add Super Admin AAB and apply the same signing, payload and 16-KB gates to it. |

## Sensitive-data boundary

Platform APIs and search results must never serialize passwords or password hashes, OTP values, activation-code hashes/secrets, access/refresh tokens, payment-provider secrets, CVV/PIN/card secrets, database credentials, signing/private keys, or environment secrets. Phone and email are masked by default. Reveal is a separate authorized request requiring a reason and creating an audit event containing the category—not the revealed value.

## Data and migration rules

- Existing rows and public/mobile API contracts remain valid; migrations are additive and safe for rolling deployment.
- Customer `created_at`, if added, is nullable for legacy accounts. Old accounts are shown as “unavailable”; they are not assigned a fabricated creation date.
- Audit rows are append-only at the database layer after migration.
- No floating-point type is used for money.
- No giant unpaginated response or Flutter-side platform aggregation is introduced.
- No hard maximum is imposed on merchant shops.

## Test and release baseline

The audited production baseline passed 1,929 backend tests and 850 Flutter tests in each of Customer, Merchant Admin, Super Admin and Worker. All eight release APKs and the existing three AABs passed release-signature and final-artifact 16-KB validation. Production smoke tests passed 45/45. The control-tower work must extend these suites and add the missing Super Admin AAB without weakening existing gates.
