# January 2026 promotional payment profiles

January 2026 lump-sum payments are not routed through generic §2.3 `allocateForward` prepayment tiers. Instead, the allocation engine recognises fixed **promotional profiles** keyed by payment month and gross amount. Each covered dues row satisfies `amount + discount_applied = getDuesRate(period)` so monthly dues reports reconcile without a `payment`-level discount flag.

**Considered options:** (1) boolean `is_discounted` on `Payment` — rejected because waivers are per-period and reports sum `PaymentAllocation` rows; (2) lower `expected_idr` for locked-rate months — rejected to keep one canonical rate schedule; (3) generic §2.3 only — rejected because year package, staff Jun/Dec waiver, and deposit-on-top are promotional flat rates, not forward-block multiples.

**Staff eligibility:** The staff year profile is gated by effective-dated staff eligibility from `house_staff_status`, evaluated on the payment date. Historical staff eligibility is not inferred from the current house row. Seed staff houses (`A/16`, `B/05`, `B/07`, `D/16`, `E/08`) are loaded by migration `V9` effective `2026-01-01`.
