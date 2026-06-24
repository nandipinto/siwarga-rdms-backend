# Resident Dues Management (RDMS)

Backend service for residential cluster monthly dues, payments, arrears, penalties, prepayment discounts, and rental-guarantee fees.

## Language

**Dues period**:
A calendar month for which a house owes monthly dues from its active date onward.
_Avoid_: billing cycle, invoice month

**Prepayment discount**:
A waiver applied to specific forward months when a clean account prepays in qualifying block sizes (6- or 12-month multiples).
_Avoid_: early-bird (unless referring specifically to the January 2026 promotion)

**January rate lock**:
Legacy Rp 100,000/month cash tariff on months covered by a January 2026 promotional payment, while report `expected_idr` stays at the **scheduled rate**; `discount_applied` is the residual (`getDuesRate(period) − cash`).
_Avoid_: discounted payment (too vague)

**January year package**:
A January 2026 payment of Rp 1,100,000 covering Jan–Dec at the locked tariff (11 × Rp 100,000 cash + December waived). Distinct from §2.3 forward prepayment tiers.
_Avoid_: early-bird prepay (ambiguous)

**Staff year package**:
A January 2026 payment of Rp 1,000,000 for staff LUNAS residents on the **staff allowlist** — full year at locked tariff with **June and December** waived (10 × Rp 100,000 cash). Detection: `gross == 1_000_000` **and** house on allowlist.
_Avoid_: prepayment discount

**Year package with deposit**:
A January 2026 payment above Rp 1,100,000 on a full-year LUNAS row: first Rp 1,100,000 is a **January year package**; `deposit = gross − 1,100,000` (laporan gross is authoritative).
_Avoid_: early-bird prepay

**Discount applied**:
The portion of a period's dues obligation satisfied without cash, recorded per allocation row. For any fully covered dues period, `amount + discount_applied` equals `getDuesRate(period)` — absorbing both prepayment-tier waivers and January rate-lock deltas.
_Avoid_: discount flag, is_discounted

**Scheduled rate**:
The canonical monthly dues for a period from the rate schedule (`getDuesRate`), used as `expected_idr` in reports and for arrears on uncovered periods.
_Avoid_: locked rate (that is the cash price under January rate lock, not the report expected)

**Report reconciliation gap**:
When the monthly dues report shows `outstanding_idr > 0` for a period that coverage replay already treats as paid — caused by `collected_idr + discount_idr < expected_idr`.
_Avoid_: false arrears (misleading — house arrears may still be zero)

## Relationships

- A **Payment** produces one or more **Payment allocation** rows (dues, penalty, or deposit lines)
- **January rate lock** applies only to the forward block after arrears and penalties are cleared in the same payment — catch-up months are always at full **scheduled rate** with no lock and no prepayment discount
- A **Prepayment discount** and **January rate lock** waiver both appear as **discount applied** on a dues allocation row
- **Report reconciliation gap** is a reporting symptom, not an unpaid period in the coverage engine

## Example dialogue

> **Dev:** "Early-bird payers show Rp 20,000 outstanding in February even though they're fully covered — is that arrears?"
> **Domain expert:** "No — that's a **report reconciliation gap**. Coverage is correct; the allocation row needs more **discount applied** so cash plus waiver equals the scheduled rate."

## Flagged ambiguities

- "False arrears" was used for monthly-report `outstanding_idr` rows — resolved: use **report reconciliation gap** when house arrears are clean but the dues report row is not.
- `EarlyBirdJan2026` parallel allocation path — resolved: refactor to **`JanuaryRateLock`** pure helpers; remove `tryAllocate` bypass from replay.
- 6-month January prepay at Rp 550,000 — resolved: single January CSV row; paid months Jan–Jun (`range(1, 7)`).
- Rp 1,320,000 full-year payments — resolved: year package + `deposit = gross − 1,100,000` (laporan gross authoritative).
- Staff tier — resolved: `gross == 1_000_000` **and** explicit `(block_code, house_number)` allowlist (seeded; extensible).
