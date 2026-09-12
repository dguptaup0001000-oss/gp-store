# Decision W1 — Money model

**Decided by the shop owner, 2026-09-05.**

> "Each merchant collects directly, but GP-STORE has all the details."

GP-STORE is a **technology marketplace**, not a payment aggregator. Money moves
between the customer and the merchant. The platform never holds, routes or settles
funds — and is the complete system of record for what happened.

This closes the highest-risk open question in the transformation and unblocks four
features that could not be designed without it.

---

## What this means, precisely

| | |
|---|---|
| Who the customer pays | The **merchant**, directly |
| Whose account receives it | The **merchant's** |
| What GP-STORE does | **Records** the order, the amount owed, the method, whether it was collected, by whom and when |
| What GP-STORE never does | Hold funds · route funds · settle to merchants · issue refunds from its own balance |

## Why this is the right call

**It keeps GP-STORE out of payments regulation.** A platform that collects customer
money and settles it onward to merchants is acting as a payment aggregator, which in
India means RBI authorisation, escrow/nodal account requirements, capital adequacy and
an audit regime. Not touching the money removes that entire category of obligation —
and it is the single largest risk reduction available to a project at this stage.

**It matches §103's philosophy.** *"Local businesses remain independent. GP-STORE
provides the technology."* A merchant who receives their own money on the same day
they earn it is independent in the way that actually matters to a small shopkeeper.
Platforms that hold takings for a settlement cycle are precisely what a kirana owner
distrusts.

**It does not weaken oversight.** "GP-STORE has all the details" is the other half of
the decision and it is load-bearing: the platform still knows every order, every
amount, every collection event and every dispute. Marketplace integrity (§20),
reporting, analytics and arbitration all work on records, not on custody of cash.

---

## Consequences

### 1. Payment methods, per shop

| Method | How it works under this model | Change needed |
|---|---|---|
| **COD (cash)** | Rider collects, it is the shop's money from the moment it is handed over | **None.** Already works exactly this way |
| **COD (UPI/QR at the door)** | Rider shows the **shop's own** UPI QR; money lands in the shop's account | Deep link built from the *shop's* VPA rather than `STORE_UPI_ID` |
| **UPI link before delivery** | Same, generated against the shop's VPA | Same |
| **Online gateway** | The merchant's own gateway account, not GP-STORE's | See §3 below — this is the part that needs care |

The existing COD cash/UPI split machinery (rider records how the money arrived) is
already the right shape: it records a collection that happened outside the platform.
That is now the *general* pattern rather than a COD special case.

### 2. Refunds — the sharp edge

**GP-STORE cannot refund money it never held.**

Today `PaymentService` calls Cashfree to issue a refund, and there is real machinery
around it: partial refunds, provider reconciliation, stuck-money alerts, a refunds
table with its own lifecycle. All of that assumes the platform controls the funds.

Under this model a refund becomes a **three-party obligation**:

```
customer requests return  →  shop approves  →  SHOP refunds the customer
                                                      │
                                              GP-STORE records:
                                                 refund owed (amount, reason, date)
                                                 refund confirmed by shop
                                                 customer confirmation / dispute
```

The platform's job changes from *executing* refunds to *tracking whether the merchant
executed them* — which is arguably a more useful thing to have, because an unpaid
refund becomes a visible, ageing, reportable obligation rather than a silent failure.

**This needs a business process, not only code.** What happens when a shop does not
refund? The platform can warn → review → restrict → suspend under §20, but somebody
has to decide the timescale and who arbitrates. That is a policy question for the shop
owner, and it is the one part of this decision that is not purely technical.

**Nothing about the existing refund code is wasted.** The refunds table, the partial
amounts, the ageing and the alerting are all reusable; only the "call the gateway"
step is replaced by "record what the merchant reported and chase it if it does not
arrive".

### 3. Merchant payment credentials — a security decision this creates

If a merchant is to accept **online card/netbanking payments** in their own name, the
platform has to interact with *their* gateway account. Three ways, in increasing risk:

| Option | What GP-STORE stores | Risk |
|---|---|---|
| **A. UPI + COD only** | The shop's VPA — a public identifier, not a secret | **Lowest.** No credential custody at all |
| **B. Gateway account linked by the merchant** (OAuth-style where the provider supports it) | A revocable token scoped to that merchant | Medium; revocable, auditable |
| **C. Merchant hands over API keys** | The merchant's **secret key**, encrypted at rest | **Highest.** GP-STORE becomes a custodian of other businesses' payment credentials — a breach compromises every merchant at once |

**Recommendation: start at A.** A kirana shop's customers pay cash or scan a QR; UPI
plus COD covers the overwhelming majority of local grocery trade, needs no credential
custody, and can ship in the next slice. Move to B only when a real merchant asks for
card payments and the provider supports delegated linking. **C should be a last
resort**, and if it is ever taken, the keys need envelope encryption and a documented
rotation and breach procedure — not a column in `shops`.

This is why Slice 0 deliberately shipped **no payment columns on `shop`**: the answer
determines whether the column holds a public VPA or a secret, and those are not the
same kind of column.

### 4. Commission (decision W2, still open) is constrained by this

If GP-STORE never touches the money, commission **cannot be deducted at source**. It
has to be either:

- **invoiced to the merchant** on a cycle (platform issues a bill; merchant pays it),
- **a flat subscription** per shop per month, or
- **nothing at all** initially.

A percentage-of-sales commission is still possible — the platform knows every order
value — but it becomes a receivable to chase rather than a deduction. A flat monthly
fee is dramatically simpler to operate and to explain to a shopkeeper. **This is now a
smaller decision than it was, and it can wait.**

### 5. Invoicing and GST

The **seller of record is the merchant**, not GP-STORE. Invoices must carry the shop's
identity, address and GSTIN — not the platform's. `invoices` already gained a
`shop_id` in Slice 0, which is exactly what this requires.

Any commission GP-STORE charges is a **separate** invoice from GP-STORE to the
merchant, for a technology service. The two must never be conflated.

---

## What changes in the plan

| Item | Before | After |
|---|---|---|
| Parity matrix #20 UPI link | Blocked | **Designed** — shop's own VPA |
| Parity matrix #21 Online payment | Blocked | **Designed** — option A first, B later |
| Parity matrix #39 Refunds | Blocked | **Designed** — obligation tracking, not execution |
| Parity matrix #68 COD collection | Blocked | **Designed** — unchanged mechanics, shop-scoped |
| Slice 10 "money routing and settlement" | Last, highest risk | **Much smaller.** No settlement engine, no escrow, no payout scheduling. It becomes: per-shop VPA, refund obligations, and invoicing |

**The riskiest slice just got a lot less risky.** There is no settlement engine to
build, no float to reconcile, no payout failures to handle.

## What is still open

- **W2 Commission** — now constrained to invoice / subscription / none
- **W3 Order numbering** — global or per-shop
- **W4 One rider per shop, or shared**
- **W5 Shop #1's real merchant and shop name**
- **New: refund policy.** How long does a merchant have to refund, and what happens if
  they do not? A platform question, not a code one.
