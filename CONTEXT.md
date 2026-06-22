# RDMS (Resident Dues Management System)

Tracks monthly dues, prepayments, penalties, and rental guarantees for houses
grouped under RTs/RWs. This glossary fixes the language of the dues/penalty domain.

## Language

### Payments & coverage

**Payment**:
A single recorded receipt of cash against a house, stamped with the date it was received.
_Avoid_: transaction, receipt (a receipt is the document, not the money).

**Payment activity**:
The fact that *some* payment was recorded in a given calendar month, regardless of amount or which months it covers. A weak signal — keyed off when cash arrived, not what it settled.
_Avoid_: using this as a proxy for whether a house is paid up.

**Coverage**:
Whether a given dues period (month) has been fully paid for via a dues allocation. A month is *covered* or *uncovered*; the boundary is `coveredThrough`.
_Avoid_: "paid" (ambiguous — paid *when* vs paid *for*).

**Arrears**:
The total dues owed for uncovered months up to the reference month.

**Prepayment**:
Coverage extending *beyond* the current month, funded by a single payment. Encouraged: a clean account earns a prepayment discount on the forward block.

### Delinquency & penalties

**Penalty**:
A fixed fine (Rp 60k at 6 months, Rp 120k at 12 months) assessed for sustained delinquency. A pure recomputed function of history — never persisted as a ledger row.

**On-time coverage**:
A month M is covered *on time* if a payment dated in month ≤ M extended coverage to reach M (monthly granularity, no within-month grace). A month covered only *retroactively* (by a later-dated payment clearing back-dues) is **not** on-time — it was delinquent while it elapsed.

**Delinquency**:
A run of consecutive months not covered **on time**. The basis for penalties. Measured *as of each month*, so a penalty once triggered **sticks** even after the resident catches up.
_Resolved 2026-06-19_: delinquency is measured by **on-time coverage**, not **payment activity** — see Flagged ambiguities.

## Relationships

- A **Payment** produces one or more dues **Coverage** allocations (oldest month first).
- **Coverage** that runs past the current month is **Prepayment**.
- Sustained lack of **Coverage** is **Delinquency**, which triggers a **Penalty**.
- **Payment activity** is *not* the basis for **Penalty** (corrected 2026-06-19).

## Flagged ambiguities

- **"consecutive months without payment"** (spec §2.4/§6.3) was implemented as
  *months with no **payment activity*** (no payment row recorded that calendar month).
  This punished **Prepayment** (a prepayer records no payment for months they have
  already covered) and let drip-payers escape (Rp 1/month = activity every month,
  arrears unbounded). **Resolved 2026-06-19**: penalties key off **Coverage**
  (delinquency), not **payment activity**.
